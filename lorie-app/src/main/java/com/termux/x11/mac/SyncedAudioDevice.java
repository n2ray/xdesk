/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AndroidAudioDevice.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name,
 * the preferred output device of MacAudio's AudioRoute (set when the track opens and re-checked while
 * playing, as MacAudio.checkRoute does), and a mute that keeps the track and its clock running.
 * SALL-SYNC.md says which Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.nio.ByteBuffer;

/** Android-only hardware boundary; every call is made by SyncedAudio's serial owner. */
final class SyncedAudioDevice implements SyncedAudioRuntime.Device {
    /** As MacAudio's ROUTE_CHECK_MS: XDesk's sound setting may change while this plays. */
    private static final long ROUTE_CHECK_MS = 500;

    /** Where the sound should go: the device XDesk's sound setting names, or null for the system's choice. */
    private final MacAudio.Output output;
    private MediaCodec codec;
    private AudioTrack track;
    private MediaFormat format;
    private SyncedAudioDecodeMapping.Encoding encoding = SyncedAudioDecodeMapping.Encoding.S16_LE;
    private Integer route;
    private int preferredId = Integer.MIN_VALUE;
    private long lastRouteCheck;
    private boolean muted;
    private final AudioTimestamp timestamp = new AudioTimestamp();

    SyncedAudioDevice(MacAudio.Output output) { this.output = output; }

    @Override public void open(byte[] cookie) throws Exception {
        close();
        format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(AacEldConfig.parse(cookie).asc()));
        startDecoder();
        int minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minimum <= 0) throw new IllegalStateException("Float stereo output unavailable");
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum, 960 * 8))
                // The fast mixer's output, as MacAudio's: without it the track went to the deep-buffer output, whose
                // own latency on HDMI was over 300 ms (dumpsys: 328 ms, MacAudio's 21 ms). The schedule counts what
                // the device holds, so the sound came 100 to 200 ms after the picture (owner, 2026-11-19)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack unavailable");
        coverQuiet = com.termux.x11.SoundCover.covered();
        track.setVolume(muted || coverQuiet ? 0f : 1f);
        applyPreferred();
        track.play(); route = null;
    }

    /** MacAudio.checkRoute at open: the device XDesk's sound setting names, or the system's choice. */
    private void applyPreferred() {
        AudioTrack t = track;
        if (t == null) return;
        AudioDeviceInfo want = output != null ? output.device() : null;
        preferredId = want != null ? want.getId() : -1;
        t.setPreferredDevice(want);
        lastRouteCheck = SystemClock.uptimeMillis();
    }

    /** MacAudio.checkRoute while playing: follow XDesk's sound setting when it changes. */
    private void checkRoute() {
        AudioTrack t = track;
        if (t == null || output == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastRouteCheck < ROUTE_CHECK_MS) return;
        lastRouteCheck = now;
        AudioDeviceInfo want = output.device();
        int id = want != null ? want.getId() : -1;
        if (id == preferredId) return;
        preferredId = id;
        t.setPreferredDevice(want);
    }

    private void startDecoder() throws Exception {
        String name = MacAudio.decoderName();
        if (name == null) throw new IllegalStateException("AAC-ELD decoder unavailable");
        codec = MediaCodec.createByCodecName(name);
        codec.configure(format, null, null, 0); codec.start();
        encoding = SyncedAudioDecodeMapping.Encoding.S16_LE;
    }
    @Override public void resetDecoder() throws Exception {
        // A new codec instance makes the flush generation boundary unambiguous even before first output.
        releaseDecoder(); startDecoder();
    }
    @Override public boolean offer(byte[] packet, long ptsUs) {
        int index = codec.dequeueInputBuffer(0);
        if (index < 0) return false;
        ByteBuffer input = codec.getInputBuffer(index);
        if (input == null || input.capacity() < packet.length) throw new IllegalStateException("Invalid AAC input buffer");
        input.clear(); input.put(packet);
        codec.queueInputBuffer(index, 0, packet.length, ptsUs, 0);
        return true;
    }
    @Override public SyncedAudioRuntime.PCM poll() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int attempt = 0; attempt < 4; attempt++) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                if (f.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 48000 || f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 2)
                    throw new IllegalStateException("Unsupported decoded PCM layout");
                int value = f.containsKey(MediaFormat.KEY_PCM_ENCODING) ? f.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                if (value == AudioFormat.ENCODING_PCM_FLOAT) encoding = SyncedAudioDecodeMapping.Encoding.FLOAT32_LE;
                else if (value == AudioFormat.ENCODING_PCM_16BIT) encoding = SyncedAudioDecodeMapping.Encoding.S16_LE;
                else throw new IllegalStateException("Unsupported decoded PCM encoding");
            } else if (index >= 0) {
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) throw new IllegalStateException("Unexpected AAC end of stream");
                    if (info.size == 0 || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) continue;
                    ByteBuffer source = codec.getOutputBuffer(index);
                    if (source == null || info.offset < 0 || info.size < 0 || info.size > 8192 * 8 * 4
                            || info.offset > source.capacity() - info.size) throw new IllegalStateException("Invalid decoded PCM buffer");
                    source = source.duplicate(); source.clear(); source.position(info.offset); source.limit(info.offset + info.size);
                    ByteBuffer copy = ByteBuffer.allocate(info.size); copy.put(source); copy.flip();
                    return new SyncedAudioRuntime.PCM(info.presentationTimeUs, copy, encoding);
                } finally { codec.releaseOutputBuffer(index, false); }
            } else return null;
        }
        return null;
    }
    @Override public SyncedAudioRuntime.Stamp timestamp() {
        return track.getTimestamp(timestamp) ? new SyncedAudioRuntime.Stamp(timestamp.framePosition, timestamp.nanoTime) : null;
    }
    @Override public int write(float[] pcm, int offset, int count) {
        checkRoute();
        // The privacy cover silences it as the mute does: the clock and the schedule go on underneath
        boolean quiet = com.termux.x11.SoundCover.covered();
        if (quiet != coverQuiet) {
            coverQuiet = quiet;
            track.setVolume(muted || quiet ? 0f : 1f);
        }
        return track.write(pcm, offset, count, AudioTrack.WRITE_NON_BLOCKING);
    }
    @Override public int bufferFrames() { return track.getBufferSizeInFrames(); }
    @Override public int underruns() { return track.getUnderrunCount(); }
    @Override public boolean routeChanged() {
        AudioDeviceInfo device = track.getRoutedDevice();
        if (device == null) return route != null;
        int id = device.getId();
        if (route == null) { route = id; return false; }
        return route != id;
    }
    /** The local mute: silence the running track without stopping it, so decoding and the clock go on. */
    @Override public void muted(boolean on) {
        muted = on;
        AudioTrack t = track;
        if (t != null) t.setVolume(on || coverQuiet ? 0f : 1f);
    }

    /** Silenced for the privacy cover (SoundCover); together with the local mute. */
    private boolean coverQuiet;
    private void releaseDecoder() {
        if (codec == null) return;
        try { codec.stop(); } catch (RuntimeException ignored) { }
        try { codec.release(); } finally { codec = null; }
    }
    @Override public void close() {
        try { releaseDecoder(); } finally {
            if (track != null) {
                try { track.pause(); track.flush(); } catch (RuntimeException ignored) { }
                try { track.release(); } finally { track = null; }
            }
            route = null;
            preferredId = Integer.MIN_VALUE;
            lastRouteCheck = 0;
        }
    }
}
