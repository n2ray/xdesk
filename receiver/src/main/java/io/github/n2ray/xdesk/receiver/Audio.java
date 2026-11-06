package io.github.n2ray.xdesk.receiver;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PCM from the phone through AudioTrack. Its own thread, so a blocking write never holds up the video sharing the
 * socket. Played as it comes; with more than 150 ms queued, new data is dropped so the sound stays with the picture.
 */
final class Audio extends Thread {
    private static final int MAX_BACKLOG_MS = 150;

    private final AudioTrack track;
    private final int rate, frameBytes;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private volatile long written;
    private volatile boolean quit;

    private Audio(AudioTrack track, int rate, int frameBytes) {
        super("audio");
        this.track = track;
        this.rate = rate;
        this.frameBytes = frameBytes;
    }

    /** Null when the format is not one this version knows */
    static Audio create(int rate, int channels, int encoding) {
        if (encoding != 1 || channels < 1 || channels > 2 || rate < 8000 || rate > 192000) {
            Log.w(MainActivity.TAG, "audio format not supported: " + rate + " " + channels + " " + encoding);
            return null;
        }
        int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int frameBytes = 2 * channels;
        try {
            int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = new AudioTrack(
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                    new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    Math.max(min, rate * frameBytes * 60 / 1000), AudioTrack.MODE_STREAM, 0);
            t.play();
            Audio a = new Audio(t, rate, frameBytes);
            a.start();
            return a;
        } catch (RuntimeException e) {
            Log.w(MainActivity.TAG, "audio: " + e);
            return null;
        }
    }

    void write(byte[] data, int off, int len) {
        long played = track.getPlaybackHeadPosition() & 0xffffffffL;
        long backlog = queuedBytes.get() / frameBytes + written - played;
        if (backlog > (long) rate * MAX_BACKLOG_MS / 1000) return;
        byte[] copy = new byte[len - len % frameBytes];
        System.arraycopy(data, off, copy, 0, copy.length);
        queuedBytes.addAndGet(copy.length);
        queue.offer(copy);
    }

    @Override
    public void run() {
        try {
            while (!quit) {
                byte[] b = queue.poll(200, TimeUnit.MILLISECONDS);
                if (b == null) continue;
                queuedBytes.addAndGet(-b.length);
                int n = track.write(b, 0, b.length);
                if (n < 0) break;
                written += n / frameBytes;
            }
        } catch (InterruptedException e) {
            // Closing
        }
    }

    void close() {
        quit = true;
        interrupt();
        try {
            join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        track.release();
    }
}
