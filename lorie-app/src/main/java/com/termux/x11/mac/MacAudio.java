/*
 * The wire format and the decoder's setup follow Sill (https://github.com/Saffsanity/sill,
 * Sources/StreamProtocol/Audio.swift) and its Android client Sall (https://github.com/n2ray/sall: AudioPlayer.java,
 * AudioPackets.java, AndroidAudioDevice.java). Copyright 2026 Noah Saffer. Licensed under the Apache License,
 * Version 2.0. The playout is XDesk's own, see below and SALL-SYNC.md.
 */
package com.termux.x11.mac;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The Mac's sound (kind 43): type 1 says how to decode (JSON AudioFormat: AAC-ELD, 48 kHz stereo, its cookie and
 * epoch), type 2 carries packets of one epoch. One thread decodes them (MediaCodec) and plays them (AudioTrack).
 *
 * Not Sall's playout: Sall schedules every packet against the video's presentation clock (AudioPlayout,
 * AudioPresentationClock, AudioScheduleJoin, about 1500 lines) and stops the sound on any underrun. XDesk shows a
 * frame as soon as it is decoded, so the sound is played as it arrives too, behind a short buffer, the way
 * XDesk's own AudioPlayer plays the Linux desktop's sound: a prefill before the track starts, and whatever piles
 * up past MAX_BACKLOG_MS is dropped so the sound never runs late. A failure here never ends the connection.
 */
final class MacAudio {
    private static final String TAG = "XDeskMac";
    private static final int RATE = 48000, FRAME = 4;
    private static final int TRACK_MS = 100, PREFILL_MS = 40, MAX_BACKLOG_MS = 200;
    private static final long IDLE_PAUSE_MS = 150, IDLE_CLOSE_MS = 10_000, ROUTE_CHECK_MS = 500;
    /** AudioMessage.maxFormatPayload and maxPacketsPayload: a larger message is dropped whole. */
    private static final int MAX_FORMAT = 4096, MAX_PACKETS = 16384;

    /** Where the sound should go: the device XDesk's sound setting names, or null for the system's choice. */
    interface Output {
        AudioDeviceInfo device();
    }

    private final Output output;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(256);
    private final Thread thread;
    private volatile boolean running = true, overflow;

    // The thread's own
    private byte[] asc;
    private int epoch = -1, framesPerPacket, primingFrames;
    private long expected = -1;
    private MediaCodec codec;
    private AudioTrack track;
    private boolean submitted, floatPcm, failed;
    private int dropFrames, prefilled, preferredId = -1;
    private long lastRouteCheck, lastPacket;
    private short[] pcm = new short[2048];
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    MacAudio(Output output) {
        this.output = output;
        thread = new Thread(this::loop, "mac-audio");
        thread.setDaemon(true);
        thread.start();
    }

    /** Sall's AudioPlayer.decoderName: a decoder that says it does AAC-ELD, or null. */
    static String decoderName() {
        try {
            for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (i.isEncoder()) continue;
                for (String type : i.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_AAC)) continue;
                    try {
                        for (MediaCodecInfo.CodecProfileLevel p : i.getCapabilitiesForType(type).profileLevels)
                            if (p.profile == MediaCodecInfo.CodecProfileLevel.AACObjectELD) return i.getName();
                    } catch (RuntimeException ignored) { }
                }
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static Boolean supported;

    static synchronized boolean supported() {
        if (supported == null) supported = decoderName() != null;
        return supported;
    }

    /** A kind 43 payload, from the connection's reader. Never blocks; the bytes are copied. */
    void receive(byte[] payload, int length) {
        if (!running || length < 1 || length > MAX_PACKETS) return;
        byte[] copy = java.util.Arrays.copyOf(payload, length);
        if (!queue.offer(copy)) {
            // The player is stuck or far behind: start over from what comes next
            queue.clear();
            overflow = true;
        }
    }

    void close() {
        running = false;
        thread.interrupt();
    }

    private void loop() {
        try {
            while (running) {
                byte[] m;
                try {
                    m = queue.poll(IDLE_PAUSE_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                try {
                    if (m == null) idle();
                    else if (m[0] == 1) format(m);
                    else if (m[0] == 2 && !failed) packets(m);
                } catch (Exception e) {
                    // Sound is extra: say it once and stay quiet until a new format (another app, Send Audio on again)
                    if (!failed) Log.w(TAG, "Mac sound stopped: " + e);
                    failed = true;
                    release();
                }
            }
        } finally {
            release();
        }
    }

    /** Nothing came for a while: the Mac is silent. The track has played out; the next sound prefills again. */
    private void idle() {
        if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            track.pause();
            prefilled = 0;
        }
        if (track != null && SystemClock.uptimeMillis() - lastPacket > IDLE_CLOSE_MS) release();
    }

    private void format(byte[] m) throws Exception {
        if (m.length > MAX_FORMAT) return;
        JSONObject f = new JSONObject(new String(m, 1, m.length - 1, StandardCharsets.UTF_8));
        if (!"aac-eld".equals(f.optString("codec")) || f.optInt("sampleRate") != RATE || f.optInt("channels") != 2)
            throw new IllegalArgumentException("unsupported audio format " + f.optString("codec"));
        int frames = f.optInt("framesPerPacket"), priming = f.optInt("primingFrames"), e = f.optInt("epoch", -1);
        if (frames < 1 || frames > 8192 || priming < 0 || priming > 8192 || e < 0 || e > 65535)
            throw new IllegalArgumentException("invalid audio format");
        byte[] cookie = Base64.decode(f.optString("cookie", ""), Base64.DEFAULT);
        AacEldConfig.Config config = AacEldConfig.parse(cookie);
        if (config.frames() != frames) throw new IllegalArgumentException("AAC frame count disagrees with stream format");
        // A new epoch is a new encoder on the Mac: a new decoder here. The track stays
        releaseCodec();
        asc = config.asc();
        epoch = e;
        framesPerPacket = frames;
        primingFrames = priming;
        expected = -1;
        failed = false;
        Log.i(TAG, "Mac sound: " + f.optString("source") + " " + f.optString("app") + ", epoch " + e + ", " + frames
                + " frames a packet");
    }

    /** type 2 · epoch UInt16 · seq UInt32 · flags UInt8 · count UInt8 · count × (length UInt16 · bytes), big endian. */
    private void packets(byte[] m) throws Exception {
        if (m.length < 9 || asc == null) return;
        ByteBuffer b = ByteBuffer.wrap(m);
        b.get();
        int e = b.getShort() & 0xffff;
        long seq = b.getInt() & 0xffffffffL;
        boolean segmentStart = (b.get() & 1) != 0;
        int count = b.get() & 255;
        if (e != epoch || count == 0) return;
        lastPacket = SystemClock.uptimeMillis();
        boolean lost = overflow;
        overflow = false;
        // More waiting than a listener should hear late (the track was blocked, or the thread was): skip to now
        if (queue.size() * (long) framesPerPacket * 1000 / RATE > MAX_BACKLOG_MS) {
            queue.clear();
            lost = true;
            Log.i(TAG, "Mac sound: dropped a backlog");
        }
        if (codec == null) {
            open();
            // A decoder that starts in the middle of a segment: its first packet has nothing to overlap with
            dropFrames = segmentStart ? primingFrames : framesPerPacket;
        } else if (segmentStart || lost || expected >= 0 && expected != seq) {
            // Sall's AudioPackets.needsFlush and primingFrames
            if (submitted) codec.flush();
            submitted = false;
            dropFrames = segmentStart ? primingFrames : framesPerPacket;
        }
        for (int i = 0; i < count; i++) {
            if (b.remaining() < 2) return;
            int length = b.getShort() & 0xffff;
            if (length > b.remaining()) return;
            feed(m, b.position(), length);
            // (Buffer): ByteBuffer's own position(int) is only there from Android 14
            ((java.nio.Buffer) b).position(b.position() + length);
            drain();
        }
        expected = seq + count;
    }

    private void open() throws Exception {
        String name = decoderName();
        if (name == null) throw new IllegalStateException("no AAC-ELD decoder");
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
        codec = MediaCodec.createByCodecName(name);
        codec.configure(format, null, null, 0);
        codec.start();
        submitted = false;
        floatPcm = false;
        if (track == null) {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(min, TRACK_MS * RATE / 1000 * FRAME)).build();
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                t.release();
                throw new IllegalStateException("AudioTrack unavailable");
            }
            AudioDeviceInfo want = output != null ? output.device() : null;
            preferredId = want != null ? want.getId() : -1;
            t.setPreferredDevice(want);
            track = t;
            prefilled = 0;
        }
    }

    private void feed(byte[] m, int offset, int length) {
        int index = codec.dequeueInputBuffer(20_000);
        if (index < 0) {
            drain();
            index = codec.dequeueInputBuffer(20_000);
        }
        if (index < 0) {
            // The decoder takes nothing: this packet is lost, and the next one starts over
            overflow = true;
            return;
        }
        ByteBuffer in = codec.getInputBuffer(index);
        if (in == null || in.capacity() < length) throw new IllegalStateException("invalid AAC input buffer");
        ((java.nio.Buffer) in).clear();
        in.put(m, offset, length);
        codec.queueInputBuffer(index, 0, length, 0, 0);
        submitted = true;
    }

    private void drain() {
        for (int attempt = 0; attempt < 8; attempt++) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                if (f.getInteger(MediaFormat.KEY_SAMPLE_RATE) != RATE || f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 2)
                    throw new IllegalStateException("unsupported decoded PCM layout");
                int encoding = f.containsKey(MediaFormat.KEY_PCM_ENCODING) ? f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        : AudioFormat.ENCODING_PCM_16BIT;
                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
                    throw new IllegalStateException("unsupported decoded PCM encoding");
                floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT;
            } else if (index >= 0) {
                try {
                    if (info.size <= 0 || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) continue;
                    ByteBuffer out = codec.getOutputBuffer(index);
                    if (out == null) continue;
                    out = out.duplicate().order(ByteOrder.nativeOrder());
                    ((java.nio.Buffer) out).limit(info.offset + info.size);
                    ((java.nio.Buffer) out).position(info.offset);
                    play(out);
                } finally {
                    codec.releaseOutputBuffer(index, false);
                }
            } else {
                return;
            }
        }
    }

    private void play(ByteBuffer out) {
        int samples = out.remaining() / (floatPcm ? 4 : 2);
        if (pcm.length < samples) pcm = new short[samples];
        if (floatPcm) {
            for (int i = 0; i < samples; i++) {
                float v = out.getFloat() * 32768f;
                pcm[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
            }
        } else {
            out.asShortBuffer().get(pcm, 0, samples);
        }
        int frames = samples / 2, skip = Math.min(dropFrames, frames);
        dropFrames -= skip;
        if (frames == skip) return;
        AudioTrack t = track;
        // Blocks while the track is full: that is what paces this thread to the sound card
        t.write(pcm, skip * 2, (frames - skip) * 2);
        if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            prefilled += frames - skip;
            if (prefilled >= PREFILL_MS * RATE / 1000) t.play();
        }
        checkRoute(t);
    }

    /** XDesk's sound setting may change while this plays (AudioRoute): follow it, as AudioPlayer does. */
    private void checkRoute(AudioTrack t) {
        long now = SystemClock.uptimeMillis();
        if (output == null || now - lastRouteCheck < ROUTE_CHECK_MS) return;
        lastRouteCheck = now;
        AudioDeviceInfo want = output.device();
        int id = want != null ? want.getId() : -1;
        if (id == preferredId) return;
        preferredId = id;
        t.setPreferredDevice(want);
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        submitted = false;
        if (c == null) return;
        try {
            c.stop();
        } catch (RuntimeException ignored) { }
        try {
            c.release();
        } catch (RuntimeException ignored) { }
    }

    private void release() {
        releaseCodec();
        AudioTrack t = track;
        track = null;
        prefilled = 0;
        if (t == null) return;
        try {
            t.pause();
            t.flush();
        } catch (RuntimeException ignored) { }
        t.release();
    }
}
