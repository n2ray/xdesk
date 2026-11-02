package com.termux.x11;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** 从本机端口取 PCM（48k 16 位立体声）播放，输出设备可随时切换。 */
final class AudioPlayer {
    static final int OUT_PHONE = 0, OUT_EXTERNAL = 1, OUT_SYSTEM = 2;

    private static final String TAG = "XDesk";
    private static final int RATE = 48000;
    private static final long ROUTE_CHECK_MS = 500;
    private static final int FRAME = 4;
    private static final int BYTES_PER_MS = RATE * FRAME / 1000;
    private static final int CHUNK = 20 * BYTES_PER_MS;
    private static final int MAX_BACKLOG = 200 * BYTES_PER_MS;
    private static final int KEEP_BACKLOG = 40 * BYTES_PER_MS;

    private final AudioManager audio;
    private final int port;
    private volatile boolean running = true;
    private volatile int output;
    private long lastRouteCheck;
    private volatile AudioTrack track;
    private volatile Socket socket;
    private final Thread thread;

    AudioPlayer(Context c, int port, int output) {
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        this.port = port;
        this.output = output;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "xdesk-audio");
        thread.start();
    }

    /** 返回 false 表示这个设备现在不存在（比如显示器没接），这时走系统默认。 */
    boolean setOutput(int o) {
        output = o;
        AudioDeviceInfo d = device(o);
        AudioTrack t = track;
        if (t != null) t.setPreferredDevice(d);
        return o == OUT_SYSTEM || d != null;
    }


    private AudioDeviceInfo device(int o) {
        int type = o == OUT_PHONE ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : o == OUT_EXTERNAL ? AudioDeviceInfo.TYPE_HDMI : -1;
        if (type < 0) return null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == type) return d;
        }
        return null;
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
        AudioDeviceInfo want = device(output);
        AudioDeviceInfo actual = t.getRoutedDevice();
        if (want != null && (actual == null || actual.getId() != want.getId())) {
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
        t.setPreferredDevice(device(output));
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
