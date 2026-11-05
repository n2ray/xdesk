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

/** 从本机端口取 PCM（48k 16 位立体声）播放，输出设备跟着 AudioRoute，边放边核对。不需要 Shizuku。 */
final class AudioPlayer {
    private static final String TAG = "XDesk";
    private static final int RATE = 48000;
    private static final long ROUTE_CHECK_MS = 500;
    private static final int FRAME = 4;
    private static final int BYTES_PER_MS = RATE * FRAME / 1000;
    private static final int CHUNK = 20 * BYTES_PER_MS;
    private static final int MAX_BACKLOG = 200 * BYTES_PER_MS;
    private static final int KEEP_BACKLOG = 40 * BYTES_PER_MS;

    private final int port;
    private volatile boolean running = true;
    private final AudioRoute route;
    private boolean hasPreferred;
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

    /** 系统可能不理会启动前设的首选设备，所以边放边核对实际路由，不对就重设。 */
    private void checkRoute(AudioTrack t) {
        long now = System.currentTimeMillis();
        if (now - lastRouteCheck < ROUTE_CHECK_MS) return;
        lastRouteCheck = now;
        AudioDeviceInfo want = route.playerDevice();
        if (want == null) {
            // 选了系统默认，或选的设备不在了
            if (hasPreferred) t.setPreferredDevice(null);
            hasPreferred = false;
            return;
        }
        hasPreferred = true;
        AudioDeviceInfo actual = t.getRoutedDevice();
        if ((actual == null || actual.getId() != want.getId())) {
            Log.i(TAG, "reroute audio to " + want.getType());
            t.setPreferredDevice(null);
            t.setPreferredDevice(want);
        }
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
            .setBufferSizeInBytes(Math.max(min, 2 * CHUNK))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build();
        AudioDeviceInfo want = route.playerDevice();
        hasPreferred = want != null;
        t.setPreferredDevice(want);
        t.play();
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
