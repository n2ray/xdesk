package io.github.n2ray.xdesk.receiver;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MediaCodec decoding straight to the SurfaceView. Used from the session's reading thread only; a second thread
 * takes the decoded frames out and shows each one at once.
 */
final class Video {
    static final int H264 = 0x68323634, H265 = 0x68323635;
    static final int FLAG_CONFIG = 1, FLAG_KEY = 2;
    // A decoder that has had no free input buffer for this long is behind: drop frames until the next key frame
    private static final long INPUT_WAIT_US = 100_000;

    interface Listener {
        void firstFrame();

        void cannotDecode(int width, int height);
    }

    private final Surface surface;
    private final Listener listener;
    private MediaCodec codec;
    private Output output;
    private String mime;
    private int width, height, maxInput;
    // The phone sends SPS/PPS once per format; kept so a reset decoder can start again from the next key frame
    private byte[] config;
    private boolean configQueued, needKey, broken, plainFormat;

    Video(Surface surface, Listener listener) {
        this.surface = surface;
        this.listener = listener;
    }

    void format(int codecId, int w, int h) {
        release();
        config = null;
        broken = false;
        plainFormat = false;
        width = w;
        height = h;
        maxInput = Math.max(w * h, 1 << 20);
        if (codecId == H264) mime = Screen.AVC;
        else if (codecId == H265) mime = Screen.HEVC;
        else {
            mime = null;
            broken = true;
            Log.w(MainActivity.TAG, "unknown codec " + Integer.toHexString(codecId));
            listener.cannotDecode(w, h);
            return;
        }
        create();
    }

    /** Queues one VIDEO_FRAME. Returns true when the phone should send a key frame. */
    boolean frame(long pts, int flags, byte[] data, int len) {
        if (mime == null || broken) return false;
        if (output != null && output.failed) {
            Log.w(MainActivity.TAG, "decoder failed, resetting");
            release();
        }
        if (codec == null && !create()) return false;
        try {
            if ((flags & FLAG_CONFIG) != 0) {
                config = Arrays.copyOf(data, len);
                int r = queue(config, config.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                configQueued = r == QUEUED;
                return r != QUEUED;
            }
            boolean key = (flags & FLAG_KEY) != 0;
            if (needKey && !key) return true;
            if (!configQueued) {
                if (config == null) return true;
                if (queue(config, config.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != QUEUED) return behind();
                configQueued = true;
            }
            int r = queue(data, len, pts, 0);
            if (r == TOO_BIG) {
                // Input buffers are smaller than this frame: start again with room for it, once per size, as a
                // decoder that ignores the size asked for would only fail again
                if (maxInput >= len + len / 2) return behind();
                maxInput = len + len / 2;
                release();
                if (!create() || !key) return true;
                return frame(pts, flags, data, len);
            }
            if (r != QUEUED) return behind();
            needKey = false;
            output.received(pts);
            return false;
        } catch (IllegalStateException e) {
            // CodecException is one: the decoder is unusable, the next frame makes a new one
            Log.w(MainActivity.TAG, "decoder error: " + e);
            release();
            return true;
        }
    }

    private boolean behind() {
        needKey = true;
        return true;
    }

    private static final int QUEUED = 0, NO_BUFFER = 1, TOO_BIG = 2;

    private int queue(byte[] data, int len, long pts, int flags) {
        int i = codec.dequeueInputBuffer(INPUT_WAIT_US);
        if (i < 0) return NO_BUFFER;
        ByteBuffer b = codec.getInputBuffer(i);
        if (b == null || b.capacity() < len) {
            codec.queueInputBuffer(i, 0, 0, 0, 0);
            return TOO_BIG;
        }
        b.clear();
        b.put(data, 0, len);
        codec.queueInputBuffer(i, 0, len, pts, flags);
        return QUEUED;
    }

    private boolean create() {
        try {
            codec = MediaCodec.createDecoderByType(mime);
            MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInput);
            if (!plainFormat) {
                f.setInteger("low-latency", 1);
                f.setInteger("priority", 0);
                // Amlogic decoders hold about 5 frames (100 ms) before showing anything; this private key makes
                // them output each frame as it is decoded (Moonlight does the same)
                f.setInteger("vdec-lowlatency", 1);
            }
            codec.configure(f, surface, null, 0);
            codec.start();
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "decoder " + mime + " " + width + "x" + height + ": " + e);
            releaseCodec();
            if (!plainFormat) {
                // Some decoders refuse keys they do not know
                plainFormat = true;
                return create();
            }
            broken = true;
            listener.cannotDecode(width, height);
            return false;
        }
        Log.i(MainActivity.TAG, "decoder " + codec.getName() + " " + mime + " " + width + "x" + height);
        output = new Output(codec, listener);
        output.start();
        configQueued = false;
        needKey = true;
        return true;
    }

    void release() {
        if (output != null) output.quit();
        output = null;
        releaseCodec();
    }

    private void releaseCodec() {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (Exception e) {
            // Already failed: release anyway
        }
        codec.release();
        codec = null;
    }

    /**
     * Shows each frame as soon as it is decoded, not by its time stamp, for the lowest latency. Logs the time from
     * a frame's data arriving to its picture every 5 seconds (logcat -s xdesk-receiver).
     */
    static final class Output extends Thread {
        private final MediaCodec codec;
        private final Listener listener;
        private final ConcurrentHashMap<Long, Long> received = new ConcurrentHashMap<>();
        volatile boolean quit, failed;

        Output(MediaCodec codec, Listener listener) {
            super("video-out");
            this.codec = codec;
            this.listener = listener;
        }

        void received(long pts) {
            // Dropped frames never come out; do not let their entries pile up
            if (received.size() > 120) received.clear();
            received.put(pts, System.nanoTime());
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long statStart = System.nanoTime(), sum = 0, max = 0;
            int frames = 0;
            boolean shown = false;
            while (!quit) {
                try {
                    int i = codec.dequeueOutputBuffer(info, 10_000);
                    if (i < 0) continue;
                    codec.releaseOutputBuffer(i, true);
                    if (!shown) {
                        shown = true;
                        listener.firstFrame();
                    }
                    Long t = received.remove(info.presentationTimeUs);
                    long now = System.nanoTime();
                    if (t != null) {
                        long d = now - t;
                        sum += d;
                        max = Math.max(max, d);
                        frames++;
                    }
                    if (now - statStart > 5_000_000_000L && frames > 0) {
                        Log.i(MainActivity.TAG, String.format("decode avg %.1fms max %.1fms, %.1f fps",
                                sum / 1e6 / frames, max / 1e6, frames * 1e9 / (now - statStart)));
                        statStart = now;
                        sum = max = 0;
                        frames = 0;
                    }
                } catch (IllegalStateException e) {
                    if (!quit) failed = true;
                    break;
                }
            }
        }

        void quit() {
            quit = true;
            try {
                join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
