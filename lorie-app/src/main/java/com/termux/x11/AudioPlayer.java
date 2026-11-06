package com.termux.x11;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** 从本机端口取 PCM（48k 16 位立体声）播放。输出设备见 AudioRoute：有系统路由就跟着它，没有才对音轨指定设备。 */
final class AudioPlayer {
    private static final String TAG = "XDesk";
    private static final int RATE = 48000;
    private static final long ROUTE_CHECK_MS = 500;
    private static final int FRAME = 4;
    private static final int BYTES_PER_MS = RATE * FRAME / 1000;
    private static final int CHUNK = 20 * BYTES_PER_MS;
    private static final int MAX_BACKLOG = 200 * BYTES_PER_MS;
    private static final int KEEP_BACKLOG = 40 * BYTES_PER_MS;
    /**
     * The track holds this much, and starts once PREFILL is written. With the 20 ms of a low latency track, a video
     * starting in Chromium (decoder setup, page work) made the data late and the first seconds crackled.
     */
    private static final int TRACK_BUFFER = 100 * BYTES_PER_MS, PREFILL = 40 * BYTES_PER_MS;

    private final int port;
    private volatile boolean running = true;
    private final AudioRoute route;
    private static final int NO_DEVICE = -1;
    /** 没有 Shizuku 时系统可能一直不理（实测接着 HDMI 时指定手机扬声器就是这样），试这么多次就停。 */
    private static final int MAX_REROUTES = 5;
    private int preferredId = NO_DEVICE, reroutes, loggedRoute = NO_DEVICE;
    private long lastRouteCheck;
    private volatile AudioTrack track;
    private volatile Socket socket;
    private final Thread thread;

    AudioPlayer(int port, AudioRoute route) {
        this.port = port;
        this.route = route;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "xdesk-audio");
        thread.start();
    }

    void close() {
        running = false;
        thread.interrupt();
        Socket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    /** 系统可能不理会启动前设的首选设备，所以边放边核对实际路由，不对就重设几次。 */
    private void checkRoute(AudioTrack t) {
        long now = System.currentTimeMillis();
        if (now - lastRouteCheck < ROUTE_CHECK_MS) return;
        lastRouteCheck = now;
        AudioDeviceInfo actual = t.getRoutedDevice();
        int actualId = actual != null ? actual.getId() : NO_DEVICE;
        if (actualId != loggedRoute) {
            loggedRoute = actualId;
            Log.i(TAG, "audio routed to type " + (actual != null ? actual.getType() : NO_DEVICE));
        }
        AudioDeviceInfo want = route.coversSystem() ? null : route.playerDevice();
        int wantId = want != null ? want.getId() : NO_DEVICE;
        if (wantId != preferredId) {
            preferredId = wantId;
            reroutes = 0;
            t.setPreferredDevice(want);
            return;
        }
        if (want == null || actualId == wantId) {
            reroutes = 0;
            return;
        }
        if (reroutes > MAX_REROUTES) return;
        if (++reroutes > MAX_REROUTES) {
            Log.w(TAG, "audio stays on type " + (actual != null ? actual.getType() : NO_DEVICE) + ", wanted " + want.getType() + ", giving up");
            route.reportNotHonoured();
            return;
        }
        Log.i(TAG, "reroute audio to " + want.getType());
        t.setPreferredDevice(null);
        t.setPreferredDevice(want);
    }

    private AudioTrack newTrack() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(Math.max(min, TRACK_BUFFER))
            .build();
        AudioDeviceInfo want = route.coversSystem() ? null : route.playerDevice();
        preferredId = want != null ? want.getId() : NO_DEVICE;
        t.setPreferredDevice(want);
        return t;
    }

    private void loop() {
        byte[] buf = new byte[CHUNK];
        while (running) {
            try (Socket s = new Socket()) {
                socket = s;
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                Log.i(TAG, "audio connected");
                InputStream in = s.getInputStream();
                AudioTrack t = newTrack();
                track = t;
                try {
                    int have = 0, n;
                    int written = 0, underruns = 0;
                    while (running && (n = in.read(buf, have, buf.length - have)) >= 0) {
                        have += n;
                        int whole = have - have % FRAME;
                        // 积压太多就丢掉，保持声音和画面同步
                        int backlog = in.available();
                        if (backlog > MAX_BACKLOG) {
                            long drop = backlog - KEEP_BACKLOG;
                            in.skip(drop - drop % FRAME);  // 只丢整数帧，左右声道才不会错位
                        }
                        t.write(buf, 0, whole);
                        if (written < PREFILL) {
                            written += whole;
                            if (written >= PREFILL) t.play();
                        } else if (t.getUnderrunCount() != underruns) {
                            underruns = t.getUnderrunCount();
                            Log.i(TAG, "audio underruns: " + underruns);
                        }
                        checkRoute(t);
                        System.arraycopy(buf, whole, buf, 0, have - whole);
                        have -= whole;
                    }
                } finally {
                    track = null;
                    t.release();
                }
            } catch (IOException e) {
                Log.d(TAG, "audio: " + e.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
