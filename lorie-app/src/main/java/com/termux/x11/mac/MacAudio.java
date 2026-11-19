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
import android.media.AudioTimestamp;
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
 * frame as soon as it is decoded, so the sound is played as it arrives too, behind a short buffer.
 * Like XDesk's own AudioPlayer for the Linux desktop: a prefill before the track starts (here adapting to the link),
 * and the oldest of what piles up past it is dropped so the sound never runs late. A failure here never ends the connection.
 */
final class MacAudio implements SoundPlayer {
    private static final String TAG = "XDeskMac";
    private static final int RATE = 48000, FRAME = 4;
    // The cushion before the track starts adapts to the link: PREFILL_MIN_MS on a steady one, one PREFILL_STEP_MS more
    // after every underrun up to PREFILL_MAX_MS (Tailscale and Wi-Fi bring packets in bursts), back down after
    // CALM_MS without one. The track is sized to the largest cushion; past the cushion plus BACKLOG_EXTRA_MS the
    // oldest packets go, and once a second the frames queued in the track are held to the cushion plus QUEUE_EXTRA_MS
    // by dropping decoded PCM, only as many frames as needed, so the sound never runs late and never drops all at once.
    private static final int PREFILL_MIN_MS = 60, PREFILL_MAX_MS = 300, PREFILL_STEP_MS = 40,
        BACKLOG_EXTRA_MS = 250, QUEUE_EXTRA_MS = 40;
    /** A calm this long takes PREFILL_STEP_MS / 2 off the cushion: back from the most to the least in about 2 minutes. */
    private static final long CALM_MS = 10_000;
    // A gap this long is the Mac going quiet, not a late packet
    private static final long IDLE_PAUSE_MS = 500, IDLE_CLOSE_MS = 10_000, ROUTE_CHECK_MS = 500;
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
    /** Muted by the "mute this device" switch or the headphones going away (AudioMute): decode, play nothing. */
    private volatile boolean muted;

    // The thread's own
    private byte[] asc;
    private int epoch = -1, framesPerPacket, primingFrames;
    private long expected = -1;
    private MediaCodec codec;
    private AudioTrack track;
    private boolean submitted, floatPcm, failed;
    private int dropFrames, prefilled, preferredId = -1;
    // The audio thread's own: the cushion now, the track's underruns as last seen, and the frames this thread has
    // written (AudioTrack's timestamp says how many of them the sound card has played)
    private int prefillMs = PREFILL_MIN_MS, underruns;
    private long calmSince;
    private long lastRouteCheck, lastPacket, framesWritten, lastQueueCheck;
    /** The counts when the queue was last re-based (a flush may or may not reset AudioTrack's position). */
    private long queueBaseWritten, queueBaseFrame;
    private boolean queueBasePending;
    /** The least queued since the last re-base: the output's own latency (a Bluetooth headset's 200 ms and more),
     *  which is not a backlog and must never be dropped. Long.MAX_VALUE: not measured yet. */
    private long queueFloor = Long.MAX_VALUE;
    private long lastReport;
    /** This track silenced for the privacy cover. */
    private boolean coverQuiet;
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

    /** The mute changed (AudioMute): the decoder stays in step, nothing is written to the track. */
    @Override public void setMuted(boolean muted) {
        this.muted = muted;
    }

    private void loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
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

    /** What waits in the queue, in ms (a message is one packet or more: this counts each as one, the least). */
    private long backlogMs() {
        return queue.size() * (long) framesPerPacket * 1000 / RATE;
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
        // More waiting than a listener should hear late (a burst after a stall): drop the oldest, down to the cushion
        if (backlogMs() > prefillMs + BACKLOG_EXTRA_MS) {
            int n = 0;
            while (backlogMs() > prefillMs && queue.poll() != null) n++;
            lost = true;
            Log.i(TAG, "Mac sound: dropped " + n + " late messages, cushion " + prefillMs + " ms");
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
            // The track holds the largest cushion, not more: what is queued past it is what would run late
            int bytes = Math.max(min, PREFILL_MAX_MS * RATE / 1000 * FRAME);
            AudioTrack t = null;
            for (int attempt = 0; attempt < 2 && t == null; attempt++) {
                AudioTrack.Builder b = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bytes);
                if (attempt == 0) {
                    try {
                        b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                    } catch (RuntimeException ignored) {
                        // No low-latency path on this output: try it without
                    }
                }
                try {
                    AudioTrack made = b.build();
                    if (made.getState() == AudioTrack.STATE_INITIALIZED) t = made;
                    else made.release();
                } catch (RuntimeException e) {
                    Log.i(TAG, "Mac sound: AudioTrack " + (attempt == 0 ? "low-latency" : "") + " mode refused: " + e);
                }
            }
            if (t == null) throw new IllegalStateException("AudioTrack unavailable");
            AudioDeviceInfo want = output != null ? output.device() : null;
            preferredId = want != null ? want.getId() : -1;
            t.setPreferredDevice(want);
            track = t;
            coverQuiet = false;
            prefilled = 0;
            framesWritten = 0;
            queueBaseWritten = 0;
            queueBaseFrame = 0;
            queueBasePending = false;
            lastQueueCheck = 0;
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
        AudioTrack t = track;
        if (frames == skip) return;
        if (muted) {
            // Muted: keep the decoder in step, play nothing. A track left running would empty itself, and on
            // unmute the first bytes would go out without the prefill, which crackles. Stop it now, so unmute
            // prefills again (and the headphone path is released once they are pulled)
            if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                t.pause();
                t.flush();
                prefilled = 0;
                queueBasePending = true;
                lastQueueCheck = 0;
                queueFloor = Long.MAX_VALUE;
            }
            return;
        }
        // Blocks while the track is full: that is what paces this thread to the sound card
        // The external screen is covered for privacy (SoundCover): silent, the stream goes on underneath
        boolean quiet = com.termux.x11.SoundCover.covered();
        if (quiet != coverQuiet) {
            coverQuiet = quiet;
            t.setVolume(quiet ? 0f : 1f);
        }
        t.write(pcm, skip * 2, (frames - skip) * 2);
        framesWritten += frames - skip;
        if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            prefilled += frames - skip;
            if (prefilled >= prefillMs * RATE / 1000) {
                t.play();
                underruns = t.getUnderrunCount();
                calmSince = SystemClock.uptimeMillis();
            }
        } else {
            adapt(t);
            checkQueue(t);
        }
        checkRoute(t);
    }

    /**
     * Once a second, how many decoded frames wait in the track: framesWritten less the position the sound card
     * reports. Past the cushion plus QUEUE_EXTRA_MS the oldest of them cannot be dropped any more, so the next
     * decoded PCM is skipped until the queue is back at the cushion (iOS AudioPlayout keeps its buffer bounded the
     * same way: what is already queued is not played late).
     */
    private void checkQueue(AudioTrack t) {
        long now = SystemClock.uptimeMillis();
        if (now - lastQueueCheck < 1000) return;
        lastQueueCheck = now;
        AudioTimestamp stamp = new AudioTimestamp();
        if (!t.getTimestamp(stamp)) return;
        // The stamp is the sound card's position at stamp.nanoTime, which can be a good while ago (the HDMI output
        // updates it seldom): what it has played by now is more. Read as now, the queue looked longer than it was,
        // the drop below took too much and an underrun followed, which raised the cushion; to 300 ms in a minute
        // (2026-11-19: every "dropping" line was followed by "underrun, cushion now ...")
        long age = Math.max(0, System.nanoTime() - stamp.nanoTime);
        long played = stamp.framePosition + Math.min(age, 1_000_000_000L) * RATE / 1_000_000_000L;
        if (queueBasePending) {
            // A flush may reset the position or continue it: whatever it says, what is queued from here is measured
            // from this stamp
            queueBasePending = false;
            queueBaseWritten = framesWritten;
            queueBaseFrame = played;
            queueFloor = Long.MAX_VALUE;
            return;
        }
        long queued = (framesWritten - queueBaseWritten) - (played - queueBaseFrame);
        if (queued <= 0) return;
        if (now - lastReport >= 60_000) {
            // A line a minute: how late the sound is (what waits in the track, the output's own part in it too)
            lastReport = now;
            Log.i(TAG, "Mac sound: " + (queued * 1000 / RATE) + " ms queued, cushion " + prefillMs + " ms, "
                    + t.getUnderrunCount() + " underruns so far");
        }
        // What the output itself holds (its latency) is in this count too: only what grew over the steady least is late
        if (queued < queueFloor) queueFloor = queued;
        long over = queued - queueFloor;
        long limit = (prefillMs + QUEUE_EXTRA_MS) * (long) RATE / 1000;
        if (over <= limit) return;
        long cushion = prefillMs * (long) RATE / 1000;
        long drop = over - cushion;
        if (drop <= 0) return;
        dropFrames = (int) Math.min(Integer.MAX_VALUE, Math.max(dropFrames, drop));
        Log.i(TAG, "Mac sound: " + (queued * 1000 / RATE) + " ms queued, dropping " + (drop * 1000 / RATE)
                + " to the " + prefillMs + " ms cushion");
    }

    /** The cushion follows the link: larger after an underrun (heard as a gap), smaller after a calm while. */
    private void adapt(AudioTrack t) {
        long now = SystemClock.uptimeMillis();
        int u = t.getUnderrunCount();
        if (u > underruns) {
            underruns = u;
            calmSince = now;
            if (prefillMs < PREFILL_MAX_MS) {
                prefillMs = Math.min(PREFILL_MAX_MS, prefillMs + PREFILL_STEP_MS);
                Log.i(TAG, "Mac sound: underrun, cushion now " + prefillMs + " ms");
            }
        } else if (now - calmSince > CALM_MS && prefillMs > PREFILL_MIN_MS) {
            calmSince = now;
            prefillMs = Math.max(PREFILL_MIN_MS, prefillMs - PREFILL_STEP_MS / 2);
        }
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
        framesWritten = 0;
        queueBaseWritten = 0;
        queueBaseFrame = 0;
        queueBasePending = false;
        lastQueueCheck = 0;
        if (t == null) return;
        try {
            t.pause();
            t.flush();
        } catch (RuntimeException ignored) { }
        t.release();
    }
}
