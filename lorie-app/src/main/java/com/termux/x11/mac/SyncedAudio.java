/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioPlayer.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name,
 * the output device of MacAudio's AudioRoute, a failure callback that names this player, and a
 * once-a-minute diagnostic (XDesk builds as Java 9). SALL-SYNC.md says which Sall commit this
 * follows and how to compare later.
 */
package com.termux.x11.mac;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sall's lip-synced AAC-ELD playout: the Mac's sound is scheduled against the same capture stamps as
 * the video, instead of played as it arrives. Audio failures never terminate the video connection;
 * MacClient falls back to MacAudio when this player reports one.
 */
final class SyncedAudio implements SoundPlayer, AutoCloseable {
    private static final String TAG = "XDeskMac";
    /** How often the measured latency and placement offset are logged (no per-packet lines). */
    private static final long DIAGNOSTIC_NS = 60_000_000_000L;

    /** A failed audio session, with the reason; the player is passed so a stale worker cannot be mistaken. */
    interface Failure {
        void failed(SyncedAudio player, String reason);
    }

    private interface Event { void apply() throws Exception; }
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mac-audio-synced"); t.setDaemon(true); return t;
    });
    private final SyncedAudioRuntime runtime;
    private final SyncedAudioRecoveryPolicy clockRecovery = new SyncedAudioRecoveryPolicy();
    private final Failure onFailure;
    // Mailbox controls are coalesced under this lock, never dropped with audio data.
    private boolean closed, requestedMuted, overflow;
    // Serial owner only.
    private boolean blocked, muted;
    private long lastDiagnosticNanos;
    private SyncedAudioPresentationClock.State lastClockState;

    private static final class Rtt {
        final double seconds;
        final long at;

        Rtt(double seconds, long at) {
            this.seconds = seconds;
            this.at = at;
        }

        double seconds() { return seconds; }
        long at() { return at; }
    }

    private final ArrayDeque<Rtt> rtts = new ArrayDeque<>();

    SyncedAudio(MacAudio.Output output, Failure onFailure) {
        runtime = new SyncedAudioRuntime(new SyncedAudioDevice(output));
        this.onFailure = onFailure;
        lastDiagnosticNanos = System.nanoTime();
        worker.schedule(this::tick, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * The worker's pace: every 5 ms while sound comes (Sall's), every IDLE_TICK_MS once none came for IDLE_NS. The
     * session lasts as long as the connection, mostly silent; 200 wakeups a second for nothing cost battery. The
     * first packet after a silence waits at most IDLE_TICK_MS, under the playout's own startup cushion.
     */
    private static final long TICK_MS = 5, IDLE_TICK_MS = 100, IDLE_NS = 2_000_000_000L;
    private volatile long lastPacketNanos;

    /** A kind 43 payload, from the connection's reader. Never blocks; the bytes are copied. */
    synchronized void receive(byte[] payload, int length, double hostStamp, long arrivalNanos) {
        if (closed || length < 1 || length > 16384) return;
        final byte[] copy = java.util.Arrays.copyOf(payload, length);
        lastPacketNanos = System.nanoTime();
        post(() -> {
            if (copy[0] == 1) configure(copy);
            else if (copy[0] == 2 && !blocked) {
                SyncedAudioPackets.Batch batch;
                try { batch = SyncedAudioPackets.parse(copy); }
                catch (java.io.IOException malformed) { return; }
                runtime.packet(batch, hostStamp, arrivalNanos, System.nanoTime());
            }
        });
    }

    synchronized void videoFrame(double hostStamp, long arrivalNanos) {
        if (!closed) post(() -> runtime.frame(hostStamp, arrivalNanos));
    }

    /** Local mute: the decoder and the presentation clock stay in step, nothing is audible. */
    @Override public synchronized void setMuted(boolean on) {
        if (!closed) requestedMuted = on;
    }

    synchronized void setRemote(boolean remote) { if (!closed) post(() -> runtime.away(remote)); }

    synchronized void timing(double refreshSeconds, double fps) {
        if (!closed) post(() -> runtime.timing(refreshSeconds, fps));
    }

    synchronized void rttSample(double seconds, long arrivalNanos) {
        if (closed || !Double.isFinite(seconds) || seconds < 0 || seconds > 60) return;
        post(() -> {
            while (!rtts.isEmpty() && arrivalNanos - rtts.peek().at() > 1_000_000_000L) rtts.remove();
            if (rtts.size() == 256) rtts.remove();
            rtts.add(new Rtt(seconds, arrivalNanos));
            double[] values = rtts.stream().mapToDouble(Rtt::seconds).sorted().toArray();
            int middle = values.length / 2;
            runtime.rtt(values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2);
        });
    }

    private void post(Event event) {
        if (events.size() >= 256) { overflow = true; return; }
        events.add(event);
    }

    private void tick() {
        try {
            step();
        } finally {
            boolean stop;
            synchronized (this) { stop = closed; }
            if (!stop && !worker.isShutdown()) {
                boolean idle = System.nanoTime() - lastPacketNanos > IDLE_NS;
                try {
                    worker.schedule(this::tick, idle ? IDLE_TICK_MS : TICK_MS, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.RejectedExecutionException ignored) { }
            }
        }
    }

    private void step() {
        try {
            boolean stop, mute, lost;
            synchronized (this) {
                stop = closed; mute = requestedMuted; lost = overflow; overflow = false;
                if (stop || lost) events.clear();
            }
            if (stop) { runtime.close(); worker.shutdown(); return; }
            if (lost) throw new IllegalStateException("audio event queue overflow");
            if (mute != muted) {
                muted = mute;
                runtime.muted(mute);
            }
            for (int i = 0; i < 32; i++) {
                Event event;
                synchronized (this) { event = events.poll(); }
                if (event == null) break;
                event.apply();
            }
            if (!blocked) {
                long now = System.nanoTime();
                if (!rtts.isEmpty() && now - rtts.peekLast().at() > 1_000_000_000L) {
                    rtts.clear(); runtime.rtt(null);
                }
                runtime.tick(now);
                logState();
                if (now - lastDiagnosticNanos >= DIAGNOSTIC_NS) {
                    lastDiagnosticNanos = now;
                    logDiagnostics();
                }
            }
        } catch (Exception e) {
            boolean first = !blocked; blocked = true;
            boolean reset = true;
            try { runtime.close(); } catch (RuntimeException ignored) { reset = false; }
            boolean retry;
            synchronized (this) {
                events.clear();
                retry = reset && !closed && clockRecovery.retry(e, System.nanoTime());
            }
            if (retry) {
                blocked = false;
                Log.i(TAG, "Mac sound: synced playout clock interrupted; restarting: " + e.getMessage());
                return;
            }
            if (first) {
                String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                if (onFailure != null) {
                    try { onFailure.failed(this, reason); }
                    catch (RuntimeException ignored) { }
                } else Log.w(TAG, "Mac sound: synced playout failed: " + reason);
            }
        }
    }

    /** One line when the playout starts and when its presentation clock becomes ready or fails. */
    private void logState() {
        SyncedAudioPresentationClock.State state = runtime.clockState();
        if (state == null) { lastClockState = null; return; }
        boolean fresh = lastClockState == null;
        if (state == lastClockState) return;
        lastClockState = state;
        if (fresh) Log.i(TAG, "Mac sound: synced playout starting");
        if (state == SyncedAudioPresentationClock.State.READY)
            Log.i(TAG, "Mac sound: synced playout ready");
        else if (state == SyncedAudioPresentationClock.State.FAILED)
            Log.w(TAG, "Mac sound: synced playout failed: " + runtime.clockFailure());
    }

    /** Once a minute: the measured write lead (latency) and the model's last placement offset. */
    private void logDiagnostics() {
        String diagnostic = runtime.diagnostics();
        if (diagnostic == null) return;
        Double offset = runtime.offsetFrames();
        Log.i(TAG, "Mac sound: synced playout: " + diagnostic
                + (offset != null && Double.isFinite(offset)
                        ? " offsetMs=" + (Math.round(offset / 48.0 * 10) / 10.0) : ""));
    }

    private void configure(byte[] data) throws Exception {
        if (data.length > 4096) throw new IllegalArgumentException("Audio format too large");
        JSONObject f = new JSONObject(new String(data, 1, data.length - 1, StandardCharsets.UTF_8));
        if (!"aac-eld".equals(f.optString("codec")) || f.optInt("sampleRate") != 48000 || f.optInt("channels") != 2)
            throw new IllegalArgumentException("Unsupported audio format");
        int frames = f.optInt("framesPerPacket"), priming = f.optInt("primingFrames"), epoch = f.optInt("epoch", -1);
        if (frames < 1 || frames > 8192 || priming < 0 || priming > 8192 || epoch < 0 || epoch > 65535)
            throw new IllegalArgumentException("Invalid audio format");
        byte[] cookie = Base64.decode(f.optString("cookie", ""), Base64.DEFAULT);
        if (cookie.length == 0 || cookie.length > 1024) throw new IllegalArgumentException("Invalid audio configuration");
        if (AacEldConfig.parse(cookie).frames() != frames) throw new IllegalArgumentException("AAC frame count disagrees with stream format");
        runtime.format(epoch, frames, priming, cookie); blocked = false;
    }

    @Override public synchronized void close() {
        closed = true; events.clear();
    }
}
