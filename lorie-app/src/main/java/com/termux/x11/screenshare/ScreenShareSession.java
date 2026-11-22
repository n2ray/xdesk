// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One connection to a Mac's own Screen Sharing (high-performance mode): DirectSession authenticates (the server's
 * SRP proof is checked), asks for one virtual display of the given size and negotiates the media with keys made
 * for this connection; AvcReceiver takes the authenticated RTP.
 *
 * The connection is the user's: it lasts from their connect until they disconnect (or XDesk ends, or the network
 * drops), whatever is on the screen meanwhile. The picture is separate: a screen showing the Mac attaches a Surface
 * (a hardware decoder is made for it, starting at the next IDR, which it asks the Mac for); taken off the screen it
 * detaches, the decoder goes and the frames that keep coming are dropped. Only one connection at a time: the media
 * ports are fixed (5900/5901 UDP), so a new one closes the old one first.
 *
 * Ported from the tested prototype (n2ray/xdesk-mac-screen-sharing 2abc482, phone-probe/Viewer.java).
 */
public final class ScreenShareSession {
    public static final String TAG = "XDeskScreenShare";

    /** The connection's life, for the app (ScreenShareSource). On the UI thread. */
    public interface Listener {
        /** Authenticated and the media negotiated. */
        void connected(ScreenShareSession s);
        /** It ended: by close() (reason null) or by an error (the reason to show). */
        void ended(ScreenShareSession s, String reason);
    }

    /** The picture's, for the screen showing it (ScreenShareActivity). On the UI thread. */
    public interface PictureListener {
        /** The first picture since attach(), this long after it (or after the connect, for the first). */
        void firstFrame(long ms);
    }

    private static ScreenShareSession current;

    public final ScreenShareStore.Host host;
    public final int width, height;
    private final Context context;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService input = Executors.newSingleThreadExecutor();
    private volatile DirectSession direct;
    private volatile AvcReceiver receiver;
    private volatile boolean closed;
    /** The decoder for the attached Surface, or null while nothing shows the picture. */
    private MediaCodec codec;
    private Thread drain;
    private PictureListener picture;
    private long startNs, attachNs, firstRenderNs, lastRenderNs;
    private int rendered;
    private boolean pictureSeen, anyPicture;
    /** Test only: the Mac's clock (its clock_meter) minus ours, and whether it was measured. */
    public volatile long offsetNs;
    public volatile boolean clockCalibrated;
    public volatile double clockRttMs = -1;
    private final ArrayList<Double> pipeline = new ArrayList<>();
    private final ArrayList<Double> ages = new ArrayList<>();

    private ScreenShareSession(Context c, ScreenShareStore.Host h, int w, int height, Listener l) {
        context = c.getApplicationContext();
        host = h;
        width = w;
        this.height = height;
        listener = l;
    }

    public static synchronized ScreenShareSession current() {
        return current;
    }

    /** Connects; the password is cleared once the authentication used it. */
    public static synchronized ScreenShareSession start(Context c, ScreenShareStore.Host h, char[] password,
                                                        int w, int height, Listener l) {
        if (current != null) current.close();
        ScreenShareSession s = new ScreenShareSession(c, h, w, height, l);
        current = s;
        Thread t = new Thread(() -> s.run(password), "screen-sharing");
        t.start();
        return s;
    }

    /** Connected, or still connecting (not ended). */
    public boolean alive() {
        return !closed;
    }

    public boolean connected() {
        return direct != null && !closed;
    }

    /** Disconnects: sockets and decoder; the Mac's display layout comes back when its sharing session ends. */
    public void close() {
        closed = true;
        DirectSession d = direct;
        if (d != null) d.close();
        input.shutdown();
        detach();
        synchronized (ScreenShareSession.class) {
            if (current == this) current = null;
        }
    }

    // --- the picture ---

    /** A screen shows the Mac: decode into this Surface from the next IDR on. */
    public synchronized void attach(Surface surface, PictureListener l) {
        detach();
        if (closed) return;
        picture = l;
        attachNs = System.nanoTime();
        pictureSeen = false;
        try {
            MediaCodec c = MediaCodec.createByCodecName(decoder());
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", width, height);
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
            c.configure(fmt, surface, null, 0);
            c.setOnFrameRenderedListener(this::rendered, ui);
            c.start();
            codec = c;
            final MediaCodec mine = c;
            drain = new Thread(() -> drain(mine), "screen-sharing-render");
            drain.start();
            Log.i(TAG, "ATTACH size=" + width + "x" + height);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "decoder", e);
            codec = null;
            return;
        }
        askKeyframe();
    }

    /** Taken off the screen: the decoder goes; the connection stays. */
    public void detach() {
        MediaCodec c;
        Thread d;
        synchronized (this) {
            c = codec;
            d = drain;
            codec = null;
            drain = null;
            picture = null;
        }
        if (c == null) return;
        try {
            if (d != null) d.join(300);
        } catch (InterruptedException ignored) { }
        try {
            c.stop();
        } catch (RuntimeException ignored) { }
        c.release();
        Log.i(TAG, "DETACH");
    }

    /** The next IDR, as soon as the Mac sends one: an FIR now and again until it comes, and a full update. */
    private void askKeyframe() {
        AvcReceiver r = receiver;
        if (r != null) r.wantKeyframe = true;
        final DirectSession d = direct;
        if (d != null) send(x -> x.requestFullUpdate());
    }

    private void rendered(MediaCodec c, long pts, long ns) {
        if (pts <= 0) return;
        long delivered = System.nanoTime();
        PictureListener l;
        long since;
        boolean first;
        synchronized (this) {
            if (rendered++ == 0) firstRenderNs = delivered;
            lastRenderNs = delivered;
            if (host.measure) pipeline.add((delivered - pts * 1000) / 1e6);
            if (pictureSeen || c != codec) return;
            pictureSeen = true;
            l = picture;
            since = (delivered - (anyPicture ? attachNs : startNs)) / 1000000;
            first = !anyPicture;
            anyPicture = true;
        }
        AvcReceiver r = receiver;
        Log.i(TAG, "FIRST_FRAME ms=" + since + (first ? " (connect)" : " (shown again)") + " firs=" + (r != null ? r.firs : -1));
        if (l != null) l.firstFrame(since);
    }

    private void drain(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!closed) {
                synchronized (this) {
                    if (codec != c) return;
                }
                int idx = c.dequeueOutputBuffer(info, 20000);
                if (idx >= 0) {
                    boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (!end && info.size > 0) c.releaseOutputBuffer(idx, System.nanoTime());
                    else c.releaseOutputBuffer(idx, false);
                    if (end) break;
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "FORMAT " + c.getOutputFormat());
                }
            }
        } catch (IllegalStateException ignored) { }
    }

    /** One access unit from the Mac: to the decoder if a screen shows the picture and it has had its IDR. */
    private void frame(byte[] data, long ns, int flags) {
        MediaCodec c;
        synchronized (this) {
            c = codec;
        }
        if (c == null) return;
        AvcReceiver r = receiver;
        if (r != null && r.wantKeyframe && (flags & 1) == 0) return;
        try {
            int idx = c.dequeueInputBuffer(20000);
            if (idx < 0) return;
            ByteBuffer buf = c.getInputBuffer(idx);
            buf.clear();
            buf.put(data);
            c.queueInputBuffer(idx, 0, data.length, ns / 1000, flags);
        } catch (IllegalStateException e) {
            // Detached meanwhile
        }
    }

    // --- input ---

    /** VNC pointer: mask bits 1 left, 2 middle, 4 right, 8/16 wheel up/down, 32/64 left/right; x, y in pixels. */
    public void pointer(int mask, int x, int y) {
        send(d -> d.pointer(mask, x, y));
    }

    /** An X keysym down or up. */
    public void key(boolean down, int keysym) {
        send(d -> d.key(down, keysym));
    }

    private interface Write { void run(DirectSession d) throws Exception; }

    private void send(Write w) {
        final DirectSession d = direct;
        if (d == null || closed || input.isShutdown()) return;
        try {
            input.execute(() -> {
                if (d != direct || closed) return;
                try {
                    w.run(d);
                } catch (Exception e) {
                    Log.w(TAG, "input: connection lost", e);
                    d.close();
                }
            });
        } catch (RuntimeException ignored) { }
    }

    // --- the connection ---

    private void run(char[] password) {
        String reason = null;
        startNs = System.nanoTime();
        Log.i(TAG, "CONNECT host=" + host.host + " size=" + width + "x" + height);
        try (DirectSession d = new DirectSession(context.getAssets(), host.host, width, height)) {
            if (closed) return;
            if (host.measure) calibrate(d.host);
            try (AvcReceiver.Reader reader = new AvcReceiver.Reader(d.video, d.host)) {
                byte[] seed;
                try {
                    seed = d.start(host.account, password);
                } finally {
                    Arrays.fill(password, (char) 0);
                }
                AvcReceiver r = new AvcReceiver(seed);
                r.feedback = d.feedback;
                receiver = r;
                direct = d;
                Log.i(TAG, "CONNECTED after " + (System.nanoTime() - startNs) / 1000000 + " ms");
                ui.post(() -> listener.connected(this));
                r.run(reader, 0, this::frame);
            }
        } catch (Exception e) {
            if (!closed) {
                Log.e(TAG, "session", e);
                reason = e.getMessage() != null ? e.getMessage() : e.toString();
            }
        } finally {
            Arrays.fill(password, (char) 0);
            direct = null;
            receiver = null;
            summary();
            close();
            final String why = reason;
            ui.post(() -> listener.ended(this, why));
        }
    }

    private void calibrate(InetAddress mac) {
        long best = Long.MAX_VALUE;
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.connect(mac, 19593);
            sock.setSoTimeout(250);
            for (int i = 0; i < 20; i++) {
                long start = System.nanoTime();
                byte[] q = ByteBuffer.allocate(12).putInt(0x6d34746d).putLong(start).array();
                sock.send(new DatagramPacket(q, q.length));
                DatagramPacket reply = new DatagramPacket(new byte[20], 20);
                try {
                    sock.receive(reply);
                } catch (SocketTimeoutException | PortUnreachableException e) {
                    if (i >= 3 && best == Long.MAX_VALUE) break;
                    continue;
                }
                long end = System.nanoTime();
                ByteBuffer v = ByteBuffer.wrap(reply.getData());
                if (reply.getLength() == 20 && v.getInt() == 0x6d34746d && v.getLong() == start && end - start < best) {
                    best = end - start;
                    offsetNs = start + best / 2 - v.getLong();
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "clock calibration", e);
        }
        clockCalibrated = best != Long.MAX_VALUE;
        clockRttMs = clockCalibrated ? best / 1e6 : -1;
        Log.i(TAG, "clock minimum RTT ms=" + (clockCalibrated ? String.format(Locale.ROOT, "%.2f", clockRttMs) : "unavailable"));
    }

    private static String decoder() {
        for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!i.isEncoder() && i.isHardwareAccelerated() && Arrays.asList(i.getSupportedTypes()).contains("video/avc"))
                return i.getName();
        }
        throw new IllegalStateException("No hardware AVC decoder");
    }

    private static String stats(ArrayList<Double> v) {
        if (v.isEmpty()) return "n=0";
        ArrayList<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return String.format(Locale.ROOT, "n=%d p50=%.1f p95=%.1f ms", s.size(), s.get(s.size() / 2),
            s.get((int) ((s.size() - 1) * .95)));
    }

    /** The test numbers, in the log: decoder output rate, receive-to-render and time-bar-to-PixelCopy. */
    public synchronized void summary() {
        Log.i(TAG, String.format(Locale.ROOT,
            "RESULT frames=%d span=%.3fs fps=%.2f phone_rtp_to_callback=[%s] marker_to_pixelcopy=[%s] clock_rtt_ms=%.2f",
            rendered, (lastRenderNs - firstRenderNs) / 1e9,
            Math.max(0, rendered - 1) * 1e9 / Math.max(1, lastRenderNs - firstRenderNs), stats(pipeline), stats(ages), clockRttMs));
    }

    /** Starts a fresh measurement window (the test's 35 seconds). */
    public synchronized void resetStats() {
        rendered = 0;
        firstRenderNs = lastRenderNs = 0;
        pipeline.clear();
        ages.clear();
    }

    /** Called for each time-bar sample the test reads off the screen (ms between drawn on the Mac and seen here). */
    public synchronized void addAge(double ms) {
        ages.add(ms);
    }
}
