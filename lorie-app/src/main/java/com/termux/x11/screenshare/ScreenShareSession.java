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
 * detaches and releases the video decoder while audio continues. Only one connection at a time: the media
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
        default void cursorChanged(CursorUpdates.Shape shape) {}
    }

    private static ScreenShareSession current;

    public final ScreenShareStore.Host host;
    /** The video and the frame buffer, in pixels: what the decoder, the surface and the pointer's coordinates use. */
    public final int width, height;
    /** 桌面缩放: the screen, the Mac's desktop (points) and the video this session asked for. */
    public final ScreenShareSizing sizing;
    /** The picture's size as the decoder found it in the stream (0 until then): the Mac may give another than asked. */
    public volatile int actualW, actualH;
    private final Context context;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService input = Executors.newSingleThreadExecutor();
    private volatile DirectSession direct;
    private volatile AvcReceiver receiver;
    private volatile boolean closed;
    /** The decoder for the attached Surface, or null while nothing shows the picture. */
    private MediaCodec codec;
    // Read on the control executor as well as the UI thread.
    private volatile boolean pictureHidden;
    private Thread drain;
    private PictureListener picture;
    private long startNs, attachNs, firstRenderNs, lastRenderNs;
    private int rendered;
    private boolean pictureSeen, anyPicture;
    /** Where the connection is, for the screen's 正在连接 card (ConnectingCard): the Mac woken, the password, the media. */
    public static final int STAGE_CONNECT = 0, STAGE_LOGIN = 1, STAGE_MEDIA = 2, STAGE_PICTURE = 3;
    public volatile int stage = STAGE_CONNECT;

    /** A picture came since the connection started (not only since this screen attached). */
    public synchronized boolean hadPicture() {
        return anyPicture;
    }
    /** Test only: the Mac's clock (its clock_meter) minus ours, and whether it was measured. */
    public volatile long offsetNs;
    public volatile boolean clockCalibrated;
    public volatile double clockRttMs = -1;
    private final ArrayList<Double> pipeline = new ArrayList<>();
    private final ArrayList<Double> ages = new ArrayList<>();

    // --- the codec (n2ray/xdesk-mac-screen-sharing de55ac3, CLAUDE-HEVC-HANDOFF.md) ---
    /**
     * Test only, set by the shell's debug broadcast (BuiltinLinux, cmd ss-codec): "avc" as a phone without an HEVC
     * decoder, "hevc-config-fail" the HEVC decoder failing to configure, "hevc-no-output" no HEVC picture coming.
     */
    public static volatile String testCodec;
    /** An HEVC decoder that started and has had its stream this long without a picture: H.264 instead. */
    private static final long FIRST_FRAME_MS = 8000;
    /**
     * HEVC Main 4:2:0 when the phone has a hardware decoder for the size, else H.264. HEVC that fails (its media, its
     * decoder, no first picture) makes the connection again once with H.264, with the same password: kept for that
     * while HEVC is in use (retryPassword), cleared when the connection ends or has fallen back.
     */
    private volatile boolean hevc;
    private volatile boolean hevcFailed, mediaFailed;
    private char[] retryPassword;
    /** Which connection (1, or 2 after the fallback): what an older one's decoder or timer reports late is ignored. */
    private volatile int attempt;
    private boolean announced;
    /** The Surface and listener of the screen showing the picture: the decoder is made again for them on fallback. */
    private Surface surface;
    private PictureListener shown;
    private CursorUpdates.Shape cursorShape;
    private Runnable firstFrameTimer;
    /** testCodec when this connection started: a test set later is the next connection's, not this one's. */
    private String test;

    /** "hevc" or "avc": the codec of the connection now. */
    public String codecName() {
        return hevc ? "hevc" : "avc";
    }

    private ScreenShareSession(Context c, ScreenShareStore.Host h, ScreenShareSizing z, Listener l) {
        context = c.getApplicationContext();
        host = h;
        sizing = z;
        width = z.videoW;
        height = z.videoH;
        listener = l;
    }

    public static synchronized ScreenShareSession current() {
        return current;
    }

    /** Connects; the password is cleared once the authentication used it. */
    public static synchronized ScreenShareSession start(Context c, ScreenShareStore.Host h, char[] password,
                                                        ScreenShareSizing z, Listener l) {
        if (current != null) current.close();
        ScreenShareSession s = new ScreenShareSession(c, h, z, l);
        // Chosen before the connection and any attach: the decoder made for a screen is of this codec
        String test = testCodec;
        s.test = test;
        String hevcDecoder = "avc".equals(test) ? null : hardware("video/hevc", s.width, s.height);
        s.hevc = hevcDecoder != null;
        if (s.hevc) s.retryPassword = password.clone();
        Log.i(TAG, "CODEC " + (s.hevc ? "hevc " + hevcDecoder : "avc (no hardware HEVC decoder for "
            + s.width + "x" + s.height + (test != null ? ", test " + test : "") + ")"));
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
        clearRetryPassword();
        synchronized (ScreenShareSession.class) {
            if (current == this) current = null;
        }
    }

    // --- the picture ---

    /** A screen shows the Mac: decode into this Surface from the next IDR on. */
    public synchronized void attach(Surface surface, PictureListener l) {
        releaseDecoder();
        if (closed) return;
        this.surface = surface;
        shown = l;
        l.cursorChanged(cursorShape);
        picture = l;
        attachNs = System.nanoTime();
        pictureSeen = false;
        boolean h = hevc;
        String mime = h ? "video/hevc" : "video/avc";
        MediaCodec c = null;
        try {
            String name = hardware(mime, width, height);
            if (name == null && !h) name = decoder();
            if (name == null) throw new IllegalStateException("no hardware " + mime + " decoder");
            c = MediaCodec.createByCodecName(name);
            if (h && "hevc-config-fail".equals(test)) throw new IllegalStateException("test: HEVC configure failure");
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
            fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
            c.configure(fmt, surface, null, 0);
            c.setOnFrameRenderedListener(this::rendered, ui);
            c.start();
            codec = c;
            final MediaCodec mine = c;
            drain = new Thread(() -> drain(mine), "screen-sharing-render");
            drain.start();
            Log.i(TAG, "ATTACH size=" + width + "x" + height + " decoder=" + name);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "decoder", e);
            if (c != null) c.release();
            codec = null;
            if (h) hevcFail("decoder configure: " + e.getMessage(), attempt);
            return;
        }
        armFirstFrameTimer();
        AvcReceiver r = receiver;
        if (r != null) r.wantKeyframe = true;
        pictureHidden = false;
        send(d -> {
            if (!pictureHidden) d.showPicture(receiver);
        });
    }

    /** Taken off the screen: the decoder goes; the connection stays. */
    public void detach() {
        synchronized (this) {
            surface = null;
            shown = null;
        }
        pictureHidden = true;
        send(d -> {
            if (pictureHidden) d.hidePicture(receiver);
        });
        releaseDecoder();
    }

    private void releaseDecoder() {
        MediaCodec c;
        Thread d;
        synchronized (this) {
            c = codec;
            d = drain;
            codec = null;
            drain = null;
            picture = null;
            // A screen that went (or a new Surface) is no reason for H.264: the wait is for an attached decoder only
            cancelFirstFrameTimer();
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
            cancelFirstFrameTimer();
            l = picture;
            since = (delivered - (anyPicture ? attachNs : startNs)) / 1000000;
            first = !anyPicture;
            anyPicture = true;
        }
        AvcReceiver r = receiver;
        Log.i(TAG, "FIRST_FRAME ms=" + since + (first ? " (connect)" : " (shown again)") + " codec=" + codecName()
            + " attempt=" + attempt + " firs=" + (r != null ? r.firs : -1));
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
                    MediaFormat f = c.getOutputFormat();
                    Log.i(TAG, "FORMAT " + f);
                    noteActual(f);
                }
            }
        } catch (IllegalStateException e) {
            // Released by a detach meanwhile: nothing. The decoder still the one in use failing: HEVC's end
            boolean mine;
            synchronized (this) {
                mine = codec == c;
            }
            if (mine) {
                Log.w(TAG, "decoder output", e);
                // Only the decoder in use counts (codec == c): an old one's late error is not this connection's
                if (hevc) hevcFail("decoder: " + e.getMessage(), attempt);
            }
        }
    }

    /** The size the stream really has (its crop): kept for the settings' "last connection" line. */
    private void noteActual(MediaFormat f) {
        int w = f.containsKey("crop-right") ? f.getInteger("crop-right") - f.getInteger("crop-left") + 1 : f.getInteger(MediaFormat.KEY_WIDTH);
        int h = f.containsKey("crop-bottom") ? f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 : f.getInteger(MediaFormat.KEY_HEIGHT);
        if (w == actualW && h == actualH) return;
        actualW = w;
        actualH = h;
        boolean asked = w == width && h == height;
        Log.i(TAG, "VIDEO " + w + "x" + h + (asked ? " as asked" : " (asked " + width + "x" + height + ")"));
        ScreenShareStore.setResult(context, host.id, sizing, w, h);
    }

    /** One access unit from the Mac: to the decoder if a screen shows the picture and it has had its IDR. */
    private void frame(byte[] data, long ns, int flags, long dueNs) {
        MediaCodec c;
        synchronized (this) {
            c = codec;
        }
        if (c == null) return;
        AvcReceiver r = receiver;
        if (r != null && r.wantKeyframe && (flags & 1) == 0) return;
        // Wait on the packet consumer. The UDP reader keeps its bounded compressed queue.
        while (!closed && !pictureHidden) {
            long wait = dueNs - System.nanoTime();
            if (wait <= 0) break;
            java.util.concurrent.locks.LockSupport.parkNanos(Math.min(wait, 10000000L));
        }
        if (closed || pictureHidden) return;
        if (hevc && "hevc-no-output".equals(test)) return;  // test: the decoder gets nothing, so shows nothing
        try {
            int idx = c.dequeueInputBuffer(20000);
            if (idx < 0) return;
            ByteBuffer buf = c.getInputBuffer(idx);
            buf.clear();
            buf.put(data);
            c.queueInputBuffer(idx, 0, data.length, ns / 1000, flags);
        } catch (IllegalStateException | java.nio.BufferOverflowException e) {
            // Detached meanwhile (released under us), or the decoder in use failing
            boolean mine;
            synchronized (this) {
                mine = codec == c;
            }
            if (mine) {
                Log.w(TAG, "decoder input", e);
                if (hevc) hevcFail("decoder input: " + e, attempt);
            }
        }
    }

    // --- HEVC's fallback ---

    /**
     * HEVC failed in connection `my`: once, and only for the connection now, its media ends so run() makes it again
     * with H.264. A late report of an older connection's decoder or timer is not this one's.
     */
    private void hevcFail(String why, int my) {
        if (!hevc || hevcFailed || closed || my != attempt) return;
        hevcFailed = true;
        Log.w(TAG, "HEVC_FALLBACK " + why + ": H.264 next");
        DirectSession d = direct;
        if (d != null) d.close();
    }

    /**
     * The wait for HEVC's first picture: only with the connection up, a decoder attached and started, and no picture
     * yet in this connection. A detach, a new Surface, a disconnect cancel it (releaseDecoder, close); an attach
     * starts it again.
     */
    private synchronized void armFirstFrameTimer() {
        cancelFirstFrameTimer();
        if (!hevc || hevcFailed || closed || codec == null || direct == null || anyPicture) return;
        final MediaCodec c = codec;
        final int my = attempt;
        Runnable timer = () -> {
            synchronized (this) {
                if (firstFrameTimer == null || codec != c || pictureSeen) return;
                firstFrameTimer = null;
            }
            hevcFail("no picture " + FIRST_FRAME_MS + " ms after the decoder started", my);
        };
        firstFrameTimer = timer;
        ui.postDelayed(timer, FIRST_FRAME_MS);
    }

    private synchronized void cancelFirstFrameTimer() {
        if (firstFrameTimer != null) ui.removeCallbacks(firstFrameTimer);
        firstFrameTimer = null;
    }

    private synchronized void clearRetryPassword() {
        if (retryPassword != null) Arrays.fill(retryPassword, (char) 0);
        retryPassword = null;
    }

    /** A hardware decoder of this type for the size, by name; null when there is none. */
    private static String hardware(String mime, int w, int h) {
        for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (i.isEncoder() || !i.isHardwareAccelerated() || !Arrays.asList(i.getSupportedTypes()).contains(mime)) continue;
            try {
                MediaCodecInfo.VideoCapabilities v = i.getCapabilitiesForType(mime).getVideoCapabilities();
                if (v != null && v.isSizeSupported(w, h)) return i.getName();
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    // --- input ---

    /** Apple pointer: mask bits 1 left, 2 right, 4 middle, 8/16 wheel up/down, 32/64 left/right; x, y in pixels. */
    public void pointer(int mask, int x, int y) {
        send(d -> d.pointer(mask, x, y));
    }

    public void wheel(int buttons, int wheel, int x, int y, int points) {
        send(d -> d.wheel(buttons, wheel, x, y, Math.min(384, points)));
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
        Log.i(TAG, "CONNECT host=" + host.host + " size=" + width + "x" + height + " (" + sizing + ") codec=" + codecName());
        try {
            Exception failure = null;
            try {
                connect(password);
            } catch (Exception e) {
                failure = e;
            }
            // HEVC that failed once its media was being set up (not the login, not a disconnect): the same connection
            // again with H.264, once, with the password kept for it. Without HEVC, or after a picture with no failure
            // of its own, an end is the end
            if (!closed && hevc && (hevcFailed || (mediaFailed && !anyPicture))) {
                char[] again;
                synchronized (this) {
                    again = retryPassword;
                    retryPassword = null;
                }
                if (again != null) {
                    Log.i(TAG, "HEVC_FALLBACK reconnecting with H.264" + (failure != null ? " after: " + failure : ""));
                    switchToAvc();
                    failure = null;
                    connect(again);
                }
            }
            if (failure != null) throw failure;
        } catch (Exception e) {
            if (!closed) {
                Log.e(TAG, "session", e);
                reason = e.getMessage() != null ? e.getMessage() : e.toString();
            }
        } finally {
            Arrays.fill(password, (char) 0);
            clearRetryPassword();
            direct = null;
            receiver = null;
            summary();
            close();
            final String why = reason;
            ui.post(() -> listener.ended(this, why));
        }
    }

    /** One connection with the codec chosen now: returns or throws when its media ends. Clears the password. */
    private void connect(char[] password) throws Exception {
        final int my = ++attempt;
        mediaFailed = false;
        try (DirectSession d = new DirectSession(context, host.host, width, height, sizing.desktopW, sizing.desktopH, !host.fixed1080)) {
            if (closed) return;
            d.cursorChanged = shape -> ui.post(() -> {
                if (closed || my != attempt) return;
                cursorShape = shape;
                if (shown != null) shown.cursorChanged(shape);
            });
            d.cursorChanged.accept(null);
            d.hevc = hevc;
            if (host.measure && my == 1) calibrate(d.host);
            try (AvcReceiver.Reader reader = new AvcReceiver.Reader(d.video, d.host)) {
                byte[] seed;
                try {
                    seed = d.start(host.account, password, v -> stage = v);
                } catch (Exception e) {
                    // Past the login the media was being set up: for HEVC a reason to try H.264
                    mediaFailed = d.configuringMedia;
                    throw e;
                } finally {
                    Arrays.fill(password, (char) 0);
                }
                stage = STAGE_PICTURE;
                AvcReceiver r = new AvcReceiver(seed, d.clock);
                if (d.hevc) r.hevc = new HevcPicture();
                r.feedback = d.feedback;
                receiver = r;
                direct = d;
                // Disconnected while this was connecting (close() found no connection to close yet): it ends here
                if (closed) {
                    d.close();
                    return;
                }
                if (pictureHidden) send(session -> {
                    if (pictureHidden) session.hidePicture(r);
                });
                Log.i(TAG, "CONNECTED after " + (System.nanoTime() - startNs) / 1000000 + " ms codec=" + codecName()
                    + " attempt=" + my);
                boolean first;
                synchronized (this) {
                    first = !announced;
                    announced = true;
                }
                if (first) ui.post(() -> listener.connected(this));
                // A decoder attached while this connected waits for its first picture from now on; HEVC that already
                // failed (its decoder, before the connection was up) ends this one at once
                if (hevcFailed) d.close();
                else armFirstFrameTimer();
                try {
                    r.run(reader, 0, this::frame);
                } catch (Exception e) {
                    // The stream itself refused before any picture (an SPS that is not Main, say)
                    mediaFailed = true;
                    throw e;
                }
            }
        } finally {
            direct = null;
            receiver = null;
            cancelFirstFrameTimer();
        }
    }

    /** HEVC gave up: the decoder of the screen showing the picture is made again, for H.264, on the same Surface. */
    private void switchToAvc() {
        Surface s;
        PictureListener l;
        synchronized (this) {
            hevc = false;
            s = surface;
            l = shown;
        }
        if (s != null) attach(s, l);
        else releaseDecoder();
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
