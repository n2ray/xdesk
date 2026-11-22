// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
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

/** M4 mode 8: a complete 480-sample AAC-ELD/SBR stereo AU per authenticated PT 101 packet. */
final class AudioReceiver {
    private static final int RATE = 48000, CHANNELS = 2, FRAME = 480;
    // AudioSpecificConfig from the M4 aacf@48000#480 stereo encoder.
    private static final byte[] CONFIG = {(byte)0xf8,(byte)0xe6,0x51,0x34,(byte)0xe0,0};
    private static final String TAG = "XDeskShareAudio";
    private final Context context;
    private final DatagramSocket socket;
    private final InetAddress host;
    private volatile SrtpStream incoming;
    private volatile boolean closed;
    private Thread worker;

    AudioReceiver(Context context, DatagramSocket socket, InetAddress host) {
        this.context = context;
        this.socket = socket;
        this.host = host;
    }

    synchronized void setKey(byte[] key) throws GeneralSecurityException {
        if (closed) return;
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

    private void receive() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        MediaCodec decoder = null;
        AudioTrack track = null;
        long frames = 0, written = 0, lost = 0, late = 0;
        try {
            socket.setSoTimeout(50);
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
            long last = -1, nextRoute = 0, nextReport = SystemClock.elapsedRealtime()+10000;
            while (!closed) {
                packet.setLength(storage.length);
                boolean received = true;
                try { socket.receive(packet); } catch (SocketTimeoutException idle) { received = false; }
                SrtpStream key = incoming;
                if (received && key != null && host.equals(packet.getAddress())
                        && packet.getLength() >= 23 && (storage[1] & 127) == 101) {
                    SrtpStream.Payload au = key.open(storage, packet.getLength());
                    if (au != null) {
                        if (key != current) { current = key; last = -1; }
                        if (au.index > last) {
                            if (last >= 0) lost += au.index - last - 1;
                            last = au.index;
                            int index = decoder.dequeueInputBuffer(2000);
                            if (index >= 0) {
                                ByteBuffer in = decoder.getInputBuffer(index);
                                int size = au.data.length <= in.remaining() ? au.data.length : 0;
                                if (size > 0) in.put(au.data);
                                decoder.queueInputBuffer(index, 0, size, frames * 10000, 0);
                                frames++;
                            } else late++;
                        } else late++;
                    }
                }
                for (int i; !closed && (i = decoder.dequeueOutputBuffer(info,0)) >= 0;) {
                    try {
                        ByteBuffer out = decoder.getOutputBuffer(i);
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
