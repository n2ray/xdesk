// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.termux.x11.AudioRoute;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.TreeMap;

/** M4 mode 8: a complete 480-sample AAC-ELD/SBR stereo AU per authenticated PT 101 packet. */
final class AudioReceiver {
    private static final int RATE = 48000, CHANNELS = 2, FRAME = 480;
    // AudioSpecificConfig from the M4 aacf@48000#480 stereo encoder.
    private static final byte[] CONFIG = {(byte)0xf8,(byte)0xe6,0x51,0x34,(byte)0xe0,0};
    // One silent AU emitted by the M4 with this format. Preserve sample time on short losses.
    private static final byte[] SILENCE = {0, 0x68, 0x34, 0};
    private static final int REORDER_MS = 20, MAX_GAP = 8;
    private static final String TAG = "XDeskShareAudio";
    private final Context context;
    private final DatagramSocket socket;
    private final InetAddress host;
    private final MediaClock clock;
    private MediaClock.Reports reports;
    private volatile SrtpStream incoming;
    private volatile boolean closed;
    private Thread worker;

    AudioReceiver(Context context, DatagramSocket socket, InetAddress host, MediaClock clock) {
        this.clock = clock;
        this.context = context;
        this.socket = socket;
        this.host = host;
    }

    synchronized void setKey(byte[] key) throws GeneralSecurityException {
        if (closed) return;
        reports = new MediaClock.Reports(key);
        incoming = new SrtpStream(key);
        if (worker == null) {
            worker = new Thread(this::receive, "screen-sharing-audio");
            worker.start();
        }
    }

    synchronized void close() {
        closed = true;
        incoming = null;
        if (worker != null) worker.interrupt();
    }

    // Keep silent desktops interactive. Expire the presentation anchor after one second of silence.
    private static boolean audible(ByteBuffer pcm) {
        for (int i = pcm.position(); i + 1 < pcm.limit(); i += 16) {
            int sample = (pcm.get(i) & 255) | (pcm.get(i + 1) << 8);
            if (Math.abs(sample) > 32) return true;
        }
        return false;
    }

    private void receive() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        MediaCodec decoder = null;
        AudioTrack track = null;
        long frames = 0, written = 0, lost = 0, late = 0;
        try {
            socket.setSoTimeout(10);
            MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, CHANNELS);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(CONFIG));
            decoder = MediaCodec.createDecoderByType("audio/mp4a-latm");
            decoder.configure(format, null, null, 0);
            decoder.start();
            int mask = AudioFormat.CHANNEL_OUT_STEREO;
            int pcm = AudioFormat.ENCODING_PCM_16BIT;
            int min = AudioTrack.getMinBufferSize(RATE, mask, pcm);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(mask).setEncoding(pcm).build())
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(Math.max(min, FRAME * CHANNELS * 2 * 5)).build();
            AudioRoute route = AudioRoute.forScreenSharing(context);
            track.setPreferredDevice(route.screenSharingDevice());
            track.play();
            byte[] storage = new byte[65535];
            DatagramPacket packet = new DatagramPacket(storage, storage.length);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            SrtpStream current = null;
            TreeMap<Long, byte[]> pending = new TreeMap<>();
            long expected = -1, gapSince = 0, originIndex = 0;
            int originStamp = 0, ssrc = 0;
            AudioTimestamp hardware = new AudioTimestamp();
            long nextTiming = 0;
            long nextRoute = 0, nextReport = SystemClock.elapsedRealtime()+10000;
            while (!closed) {
                packet.setLength(storage.length);
                boolean received = true;
                try { socket.receive(packet); } catch (SocketTimeoutException idle) { received = false; }
                SrtpStream key = incoming;
                if (received && key != null && host.equals(packet.getAddress()) && packet.getLength() >= 2
                        && (storage[1] & 255) == 200) {
                    reports.accept(storage, packet.getLength(), clock.audio);
                }
                if (received && key != null && host.equals(packet.getAddress())
                        && packet.getLength() >= 23 && (storage[1] & 127) == 101) {
                    SrtpStream.Payload au = key.open(storage, packet.getLength());
                    if (au != null) {
                        if (key != current) {
                            current = key;
                            pending.clear();
                            expected = au.index;
                            originIndex = au.index;
                            ByteBuffer header = ByteBuffer.wrap(storage);
                            originStamp = header.getInt(4);
                            ssrc = header.getInt(8);
                            gapSince = 0;
                        }
                        if (au.index < expected || pending.putIfAbsent(au.index, au.data) != null) late++;
                        if (pending.size() > 12) {
                            long skipped = pending.lastKey() - expected;
                            expected = pending.lastKey();
                            pending.headMap(expected).clear();
                            late += skipped;
                            gapSince = 0;
                        }
                    }
                }
                while (!pending.isEmpty() && !closed) {
                    long gap = pending.firstKey() - expected;
                    if (gap > 0) {
                        long now = SystemClock.elapsedRealtime();
                        if (gapSince == 0) gapSince = now;
                        if (now - gapSince < REORDER_MS && pending.size() <= MAX_GAP) break;
                        if (gap > MAX_GAP) {
                            lost += gap;
                            expected += gap;
                            continue;
                        }
                    }
                    int index = decoder.dequeueInputBuffer(0);
                    if (index < 0) break;
                    byte[] data = gap == 0 ? pending.remove(expected) : SILENCE;
                    ByteBuffer in = decoder.getInputBuffer(index);
                    int size = data.length <= in.remaining() ? data.length : 0;
                    if (size > 0) in.put(data);
                    long sample = Integer.toUnsignedLong(originStamp) + (expected - originIndex) * FRAME;
                    decoder.queueInputBuffer(index, 0, size, sample * 1000000L / RATE, 0);
                    if (gap > 0) lost++;
                    else gapSince = 0;
                    frames++;
                    expected++;
                }
                for (int i; !closed && (i = decoder.dequeueOutputBuffer(info,0)) >= 0;) {
                    try {
                        ByteBuffer out = decoder.getOutputBuffer(i);
                        long now = System.nanoTime();
                        if (now >= nextTiming && audible(out) && track.getTimestamp(hardware)) {
                            long due = hardware.nanoTime + (written / 4 - hardware.framePosition) * 1000000000L / RATE;
                            if (Math.abs(due - now) < 500000000L) {
                                int sample = (int)((info.presentationTimeUs * RATE + 500000) / 1000000);
                                clock.played(ssrc, sample, due);
                            }
                            nextTiming = now + 50000000L;
                        }
                        while (!closed && out.hasRemaining()) {
                            int n = track.write(out, out.remaining(), AudioTrack.WRITE_BLOCKING);
                            if (n <= 0) throw new IOException("AudioTrack write " + n);
                            written += n;
                        }
                    } finally { decoder.releaseOutputBuffer(i,false); }
                }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextRoute) {
                    AudioDeviceInfo want = route.screenSharingDevice();
                    AudioDeviceInfo pref = track.getPreferredDevice();
                    if ((want == null ? -1 : want.getId()) != (pref == null ? -1 : pref.getId()))
                        track.setPreferredDevice(want);
                    nextRoute = now + 500;
                }
                if (now >= nextReport) {
                    Log.i(TAG,"au="+frames+" pcmFrames="+written/4+" lost="+lost+" late="+late
                            +" audioHz="+clock.audio.rateHz()+" videoHz="+clock.video.rateHz()+" videoWaitMs="+clock.lastVideoWaitMs
                            +" underruns="+track.getUnderrunCount()+" playHead="+Integer.toUnsignedLong(track.getPlaybackHeadPosition()));
                    nextReport = now + 10000;
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            if (!closed) Log.w(TAG,"Audio playback stopped",failure);
        } finally {
            if (track != null) track.release();
            if (decoder != null) decoder.release();
        }
    }
}
