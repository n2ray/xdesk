package com.termux.x11;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import com.termux.x11.app.R;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streams a display to XDesk Receiver on another device, see receiver/PROTOCOL.md. The display is made by an injector
 * of its own (public and trusted, like AppDisplay), so XDesk uses it like a cable-connected external display: desktop,
 * Android apps, cursor, dashboard and density all work as there, TargetDisplay counts it as real. Its picture goes into
 * a hardware H.264 encoder. Sound is the phone's whole output, recorded by that injector (OutputCapture), so nothing
 * plays on the phone meanwhile.
 *
 * If that injector dies (Shizuku killed, the built-in server restarted), the connection to the receiver stays and a new
 * injector makes a new display, a few times. Apps on the old display go to the phone as from an unplugged display.
 */
final class WirelessDisplay {
    interface Listener {
        /** Connecting, streaming or stopped. Main thread. */
        void onWirelessChanged();
        /** The display is there (again). Main thread. */
        void onWirelessDisplay(int displayId);
        /** The receiver shows a code. Call reply.answer with what the user typed, or with null to give up. Main thread. */
        void onWirelessPairing(String receiverName, boolean wrongBefore, Pairing reply);
        void onWirelessMessage(String message);
    }

    interface Pairing {
        void answer(String code);
    }

    private static final String TAG = "XDeskWireless";
    static final int PORT = 47140;
    static final String SERVICE_TYPE = "_xdesk-display._tcp";
    private static final byte[] MAGIC = {'X', 'D', 'S', 'K'};
    private static final int VERSION = 1;
    private static final int OK = 0, PAIR = 1, BUSY = 2, BAD_VERSION = 3, REFUSED = 4;
    private static final int VIDEO_FORMAT = 1, VIDEO_FRAME = 2, AUDIO_FORMAT = 3, AUDIO_DATA = 4, PING = 5, BYE = 6,
        SIZE = 7, KEY_FRAME = 8;
    /** Browser → phone only (web display): relative mouse, buttons, wheel steps, Linux key codes, typed text. */
    private static final int IN_MOVE = 20, IN_BUTTON = 21, IN_SCROLL = 22, IN_KEY = 23, IN_TEXT = 24;
    private static final int CODEC_H264 = 0x68323634;
    private static final long PING_AFTER_MS = 2000;
    private static final int TIMEOUT_MS = 6000, CONNECT_MS = 5000, PAIRING_MS = 120000, INJECTOR_MS = 15000;
    private static final int MAX_PAYLOAD = 16 << 20;
    /** New injectors after one died, before giving up. */
    private static final int MAX_INJECTOR_RETRIES = 3;
    /** Pixels per second at 1920x1080 and 60 fps; text on a desktop needs a fair rate. */
    private static final int BITRATE_1080P = 12_000_000, MIN_BITRATE = 4_000_000;

    static final int IDLE = 0, CONNECTING = 1, STREAMING = 2;

    private static WirelessDisplay instance;
    /** For TargetDisplay, which asks from anywhere. */
    private static volatile int displayId = Display.INVALID_DISPLAY;

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private Listener listener;

    private volatile int state = IDLE;
    private volatile String receiverName, receiverId, address;
    private volatile boolean running, keyFrameWanted, soundCaptured, web;
    private volatile Socket socket;
    private volatile WebSocket webSocket;
    private volatile InputSink webInput;
    private DataOutputStream out;
    private final Object writeLock = new Object();
    private volatile long lastWrite;
    private final LinkedBlockingQueue<String> codes = new LinkedBlockingQueue<>();
    private Thread worker;

    static synchronized WirelessDisplay get(Context c) {
        if (instance == null) instance = new WirelessDisplay(c.getApplicationContext());
        return instance;
    }

    private WirelessDisplay(Context c) {
        context = c;
        prefs = c.getSharedPreferences("wireless", Context.MODE_PRIVATE);
    }

    /** The display being streamed, or INVALID_DISPLAY. */
    static int displayId() {
        return displayId;
    }

    void setListener(Listener l) {
        listener = l;
    }

    int state() {
        return state;
    }

    boolean isActive() {
        return state != IDLE;
    }

    /** Whether the phone's sound goes to the receiver. */
    boolean capturesSound() {
        return soundCaptured;
    }

    String receiverName() {
        return receiverName;
    }

    /** The address typed last, for the address field. */
    String lastAddress() {
        return prefs.getString("last_address", "");
    }

    /**
     * Main thread. Stops a running stream first.
     *
     * @param name and id as the receiver announces them, null for an address the user typed
     */
    void start(final String host, final int port, String name, String id) {
        stop();
        if (name == null) prefs.edit().putString("last_address", host).apply();
        receiverId = id != null ? id : prefs.getString("id_at_" + host, null);
        address = host;
        running = true;
        web = false;
        receiverName = name != null ? name : host;
        codes.clear();
        setState(CONNECTING);
        context.startForegroundService(new Intent(context, DesktopService.class)
            .putExtra(DesktopService.EXTRA_TITLE, context.getString(R.string.xd_wireless_title, receiverName)));
        worker = new Thread(() -> run(host, port), "xdesk-wireless");
        worker.start();
    }

    /** Main thread. */
    void stop() {
        if (!running) return;
        running = false;
        codes.offer("");
        final Socket s = socket;
        final WebSocket w = webSocket;
        // Off the main thread: a write may block on a slow network
        new Thread(() -> {
            try {
                writeMessage(BYE, new byte[0], 0, 0);
            } catch (IOException ignored) { }
            if (w != null) w.close();
            closeQuietly(s);
        }, "xdesk-wireless-bye").start();
    }

    private void setState(final int s) {
        state = s;
        ui.post(() -> {
            if (listener != null) listener.onWirelessChanged();
            if (s == IDLE) DesktopService.stopIfIdle(context);
        });
    }

    private void message(final int id, final Object... args) {
        ui.post(() -> {
            if (listener != null) listener.onWirelessMessage(context.getString(id, args));
        });
    }

    // --- 连接和配对 ---

    private void run(String host, int port) {
        Socket s = new Socket();
        try {
            socket = s;
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), CONNECT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
            // While pairing nothing is read, so this only limits a receiver that does not answer
            s.setSoTimeout(TIMEOUT_MS);
            int[] size = handshake(in);
            if (size == null) return;
            s.setSoTimeout(TIMEOUT_MS);
            session(in, size[0], size[1], size[2]);
        } catch (NoInjector e) {
            message(R.string.xd_wireless_need_injector);
        } catch (IOException e) {
            Log.i(TAG, "ended: " + e);
            if (running) message(R.string.xd_wireless_lost, receiverName);
        } finally {
            ended(s);
        }
    }

    /** After the handshake, for a receiver and for a browser alike: the stream until it ends. */
    private void session(DataInputStream in, int width, int height, int dpi) throws IOException {
        startReader(in);
        startPings();
        int retries = 0;
        while (running) {
            boolean injectorDied = stream(width, height, dpi);
            if (!injectorDied || !running) break;
            if (++retries > MAX_INJECTOR_RETRIES) {
                message(R.string.xd_wireless_injector_lost);
                break;
            }
            Log.i(TAG, "injector died, new one, try " + retries);
            SystemClock.sleep(1000);
        }
    }

    private void ended(Socket s) {
        running = false;
        WebSocket w = webSocket;
        if (w != null) w.close();
        closeQuietly(s);
        socket = null;
        webSocket = null;
        displayId = Display.INVALID_DISPLAY;
        soundCaptured = false;
        setState(IDLE);
    }

    // --- 网页显示：浏览器经 WebServer 配对后，从这里开始和接收端走同一套消息 ---

    /** Whether the stream goes to a browser (WebServer) rather than XDesk Receiver. */
    boolean isWeb() {
        return web;
    }

    /** Where the browser's mouse and keyboard go, set by XDeskActivity. Called on the main thread. */
    void setWebInput(InputSink sink) {
        webInput = sink;
    }

    /**
     * A browser paired through WebServer: stream to it over its WebSocket. The caller checked isActive() first.
     * Any thread.
     */
    void startWeb(final Socket s, final WebSocket ws, String name, final int width, final int height) {
        running = true;
        web = true;
        address = null;
        receiverId = null;
        receiverName = name;
        codes.clear();
        setState(CONNECTING);
        context.startForegroundService(new Intent(context, DesktopService.class)
            .putExtra(DesktopService.EXTRA_TITLE, context.getString(R.string.xd_wireless_title, receiverName)));
        worker = new Thread(() -> {
            try {
                socket = s;
                webSocket = ws;
                s.setTcpNoDelay(true);
                s.setSoTimeout(TIMEOUT_MS);
                out = new DataOutputStream(ws.output());
                session(new DataInputStream(ws.input()), width, height, 0);
            } catch (NoInjector e) {
                message(R.string.xd_wireless_need_injector);
            } catch (IOException e) {
                Log.i(TAG, "web ended: " + e);
                if (running) message(R.string.xd_wireless_lost, receiverName);
            } finally {
                ended(s);
            }
        }, "xdesk-web-display");
        worker.start();
    }

    /** Browser → phone input, inside the same framing; only a browser sends these. */
    private void webInput(int type, byte[] b) {
        final InputSink sink = webInput;
        if (sink == null || !web) return;
        final ByteBuffer p = ByteBuffer.wrap(b);
        Runnable r;
        switch (type) {
            case IN_MOVE:
                if (b.length < 4) return;
                final int dx = p.getShort(), dy = p.getShort();
                r = () -> sink.move(dx, dy);
                break;
            case IN_BUTTON:
                if (b.length < 2) return;
                final int button = b[0] & 0xff;
                final boolean down = b[1] != 0;
                if (button < 1 || button > 3) return;
                r = () -> sink.button(button, down);
                break;
            case IN_SCROLL:
                if (b.length < 4) return;
                final int sy = p.getShort(), sx = p.getShort();
                r = () -> sink.scroll(sy, sx);
                break;
            case IN_KEY:
                if (b.length < 3) return;
                final int scan = p.getShort() & 0xffff;
                final boolean pressed = b[2] != 0;
                if (scan == 0 || scan > 767) return;
                r = () -> sink.key(scan, pressed);
                break;
            case IN_TEXT:
                if (b.length == 0 || b.length > 4096) return;
                final String text = new String(b, StandardCharsets.UTF_8);
                r = () -> sink.text(text);
                break;
            default:
                return;
        }
        ui.post(r);
    }

    /** Returns width, height and density, or null when the receiver said no or the user gave up. */
    private int[] handshake(DataInputStream in) throws IOException {
        synchronized (writeLock) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            writeString(phoneId());
            writeString(phoneName());
            String id = receiverId;
            writeString(id != null ? prefs.getString("token_" + id, "") : "");
            out.flush();
        }
        boolean wrong = false;
        while (true) {
            int status = in.readUnsignedByte();
            String id = readString(in), name = readString(in);
            if (!name.isEmpty()) receiverName = name;
            if (status == OK) {
                String token = readString(in);
                SharedPreferences.Editor e = prefs.edit().putString("id_at_" + address, id);
                if (!token.isEmpty()) e.putString("token_" + id, token);
                e.apply();
                receiverId = id;
                int w = in.readUnsignedShort(), h = in.readUnsignedShort(), dpi = in.readUnsignedShort();
                in.readUnsignedShort();  // refresh rate: the encoder runs at up to 60 anyway
                in.readUnsignedByte();   // codecs: H.264 is always there
                Log.i(TAG, "receiver " + receiverName + " wants " + w + "x" + h + " at " + dpi + " dpi");
                return new int[]{w, h, dpi};
            }
            if (status == PAIR) {
                String code = askCode(wrong);
                if (code == null) return null;
                synchronized (writeLock) {
                    writeString(code);
                    out.flush();
                }
                wrong = true;
                continue;
            }
            message(status == BUSY ? R.string.xd_wireless_busy : status == BAD_VERSION ? R.string.xd_wireless_version
                : R.string.xd_wireless_refused, receiverName);
            return null;
        }
    }

    private String askCode(final boolean wrongBefore) {
        codes.clear();
        final String name = receiverName;
        ui.post(() -> {
            if (listener != null) listener.onWirelessPairing(name, wrongBefore, code -> codes.offer(code != null ? code : ""));
            else codes.offer("");
        });
        try {
            String code = codes.poll(PAIRING_MS, TimeUnit.MILLISECONDS);
            return code == null || code.isEmpty() || !running ? null : code;
        } catch (InterruptedException e) {
            return null;
        }
    }

    /** Made once per install, so the receiver remembers this phone. */
    private String phoneId() {
        String id = prefs.getString("phone_id", null);
        if (id == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format("%02x", x));
            id = s.toString();
            prefs.edit().putString("phone_id", id).apply();
        }
        return id;
    }

    private String phoneName() {
        String n = Settings.Global.getString(context.getContentResolver(), "device_name");
        return n != null && !n.isEmpty() ? n : Build.MODEL;
    }

    private void writeString(String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        if (n > 1024) throw new IOException("string of " + n + " bytes");
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    // --- 消息 ---

    private void writeMessage(int type, byte[] head, ByteBuffer data, int size) throws IOException {
        synchronized (writeLock) {
            DataOutputStream o = out;
            if (o == null) throw new IOException("not connected");
            o.writeByte(type);
            o.writeInt(head.length + size);
            o.write(head);
            if (size > 0) {
                byte[] b = new byte[size];
                data.get(b);
                o.write(b);
            }
            o.flush();
            lastWrite = SystemClock.uptimeMillis();
        }
    }

    private void writeMessage(int type, byte[] payload, int offset, int size) throws IOException {
        synchronized (writeLock) {
            DataOutputStream o = out;
            if (o == null) throw new IOException("not connected");
            o.writeByte(type);
            o.writeInt(size);
            o.write(payload, offset, size);
            o.flush();
            lastWrite = SystemClock.uptimeMillis();
        }
    }

    private void startReader(final DataInputStream in) {
        new Thread(() -> {
            try {
                while (running) {
                    int type = in.readUnsignedByte();
                    int size = in.readInt();
                    if (size < 0 || size > MAX_PAYLOAD) throw new IOException("bad size " + size);
                    byte[] b = new byte[size];
                    in.readFully(b);
                    if (type >= IN_MOVE) webInput(type, b);
                    else if (type == KEY_FRAME) keyFrameWanted = true;
                    else if (type == BYE) {
                        message(R.string.xd_wireless_closed, receiverName);
                        running = false;
                        break;
                    } else if (type == SIZE) Log.i(TAG, "receiver changed its size, staying at the first one");
                }
            } catch (SocketTimeoutException e) {
                if (running) Log.i(TAG, "receiver silent");
            } catch (IOException e) {
                if (running) Log.i(TAG, "reader: " + e);
            }
            // The worker notices on its next write
            closeQuietly(socket);
        }, "xdesk-wireless-reader").start();
    }

    private void startPings() {
        new Thread(() -> {
            byte[] none = new byte[0];
            while (running) {
                SystemClock.sleep(1000);
                if (SystemClock.uptimeMillis() - lastWrite < PING_AFTER_MS) continue;
                try {
                    writeMessage(PING, none, 0, 0);
                } catch (IOException e) {
                    break;
                }
            }
        }, "xdesk-wireless-ping").start();
    }

    // --- 显示器、编码、声音 ---

    private static final class NoInjector extends IOException {
        NoInjector() {
            super("no injector");
        }
    }

    /** Returns true when the injector died and a new one may continue the stream. */
    private boolean stream(int width, int height, int receiverDpi) throws IOException {
        final IInjector[] got = new IInjector[1];
        final CountDownLatch connected = new CountDownLatch(1);
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (binder != null && binder.pingBinder()) got[0] = IInjector.Stub.asInterface(binder);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        final Boolean[] viaShizuku = new Boolean[1];
        final CountDownLatch opened = new CountDownLatch(1);
        ui.post(() -> {
            viaShizuku[0] = AppSession.get(context).openWirelessInjector(connection);
            opened.countDown();
        });
        try {
            opened.await();
            if (viaShizuku[0] == null) throw new NoInjector();
            if (!connected.await(INJECTOR_MS, TimeUnit.MILLISECONDS) || got[0] == null) {
                final boolean shizuku = viaShizuku[0];
                ui.post(() -> AppSession.get(context).closeWirelessInjector(connection, shizuku));
                throw new NoInjector();
            }
        } catch (InterruptedException e) {
            throw new NoInjector();
        }
        IInjector i = got[0];
        final boolean[] died = new boolean[1];
        IBinder.DeathRecipient death = () -> died[0] = true;
        MediaCodec encoder = null;
        Surface input = null;
        ParcelFileDescriptor sound = null;
        try {
            i.asBinder().linkToDeath(death, 0);
            // Linked to XDesk: if XDesk dies, the display and the recording end with it
            i.attach(NO_EVENTS, context.getPackageName());
            i.setEnabled(false);
            encoder = encoder(width, height);
            input = encoder.createInputSurface();
            encoder.start();
            // As an external monitor of that width: 2560 wide is about 213 dpi, 1920 is 160
            int dpi = Math.max(120, Math.round(width * 213f / 2560f));
            int id;
            try {
                id = i.createVirtualDisplay("XDesk wireless", width, height, dpi, input);
            } catch (IllegalStateException e) {
                message(R.string.xd_wireless_no_display, ShellText.localize(context, String.valueOf(e.getMessage())));
                running = false;
                return false;
            }
            displayId = id;
            Log.i(TAG, "display " + id + ", " + width + "x" + height + " " + dpi + " dpi, receiver " + receiverDpi + " dpi");
            sound = captureSound(i);
            setState(STREAMING);
            final int shown = id;
            ui.post(() -> {
                if (listener != null) listener.onWirelessDisplay(shown);
            });
            ByteBuffer format = ByteBuffer.allocate(8).putInt(CODEC_H264).putShort((short) width).putShort((short) height);
            writeMessage(VIDEO_FORMAT, format.array(), 0, 8);
            pump(encoder, died, id);
            return died[0];
        } catch (RemoteException e) {
            // The injector died while being set up
            return true;
        } finally {
            displayId = Display.INVALID_DISPLAY;
            soundCaptured = false;
            if (sound != null) closeQuietly(sound);
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (IllegalStateException ignored) { }
                encoder.release();
            }
            if (input != null) input.release();
            try {
                i.asBinder().unlinkToDeath(death, 0);
            } catch (RuntimeException ignored) { }
            // Also when it only lost its display; a dead one just throws
            try {
                i.shutdown();
            } catch (RemoteException | RuntimeException ignored) { }
            final boolean shizuku = viaShizuku[0];
            ui.post(() -> {
                AppSession.get(context).closeWirelessInjector(connection, shizuku);
                if (listener != null) listener.onWirelessChanged();
            });
        }
    }

    private void pump(MediaCodec encoder, boolean[] died, int id) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        android.hardware.display.DisplayManager dm = context.getSystemService(android.hardware.display.DisplayManager.class);
        long checked = SystemClock.uptimeMillis();
        while (running && !died[0]) {
            // The injector may be alive with its display gone, as when its session was ended: treat it as dead
            if (SystemClock.uptimeMillis() - checked > 1000) {
                checked = SystemClock.uptimeMillis();
                if (dm.getDisplay(id) == null) {
                    Log.i(TAG, "display " + id + " is gone");
                    died[0] = true;
                    break;
                }
            }
            if (keyFrameWanted) {
                keyFrameWanted = false;
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                encoder.setParameters(b);
            }
            int index = encoder.dequeueOutputBuffer(info, 100_000);
            if (index < 0) continue;
            try {
                if (info.size > 0) {
                    ByteBuffer data = encoder.getOutputBuffer(index);
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    int flags = ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ? 1 : 0)
                        | ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ? 2 : 0);
                    byte[] head = ByteBuffer.allocate(9).putLong(info.presentationTimeUs).put((byte) flags).array();
                    writeMessage(VIDEO_FRAME, head, data, info.size);
                }
            } finally {
                encoder.releaseOutputBuffer(index, false);
            }
        }
    }

    /** Low latency H.264 as h10cast's sender: no B-frames, a frame every 100 ms even when nothing moves. */
    private static MediaCodec encoder(int w, int h) throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        long rate = (long) BITRATE_1080P * w * h / (1920L * 1080L);
        f.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.max(MIN_BITRATE, rate));
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        // The receiver would otherwise keep an old picture until something moves
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
        f.setFloat("max-fps-to-encoder", 60);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        f.setInteger(MediaFormat.KEY_LATENCY, 1);
        // B-frames make the decoder hold frames back to reorder them
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        // Qualcomm's low latency mode; other vendors ignore the key
        f.setInteger("vendor.qti-ext-enc-low-latency.enable", 1);
        MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return c;
    }

    /** The phone's sound output into AUDIO_DATA. Without it the picture still goes, and sound stays on the phone. */
    private ParcelFileDescriptor captureSound(IInjector i) {
        final ParcelFileDescriptor pfd;
        try {
            pfd = i.captureOutput();
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "no sound capture", e);
            message(R.string.xd_wireless_no_sound, ShellText.localize(context, String.valueOf(e.getMessage())));
            return null;
        }
        soundCaptured = true;
        new Thread(() -> {
            byte[] buf = new byte[OutputCapture.RATE * 4 / 50];
            try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                ByteBuffer format = ByteBuffer.allocate(6).putInt(OutputCapture.RATE).put((byte) 2).put((byte) 1);
                writeMessage(AUDIO_FORMAT, format.array(), 0, 6);
                while (running) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    byte[] head = ByteBuffer.allocate(8).putLong(System.nanoTime() / 1000).array();
                    writeMessage(AUDIO_DATA, head, ByteBuffer.wrap(buf, 0, n), n);
                }
            } catch (IOException e) {
                Log.i(TAG, "sound: " + e.getMessage());
            }
        }, "xdesk-wireless-sound").start();
        ui.post(() -> {
            if (listener != null) listener.onWirelessChanged();
        });
        return pfd;
    }

    /** The injector only needs XDesk's binder to notice XDesk dying; it sends nothing here. */
    private static final IInjectorListener NO_EVENTS = new IInjectorListener.Stub() {
        @Override public void onCursor(int x, int y) { }
        @Override public void onForwarding(boolean on, int deviceCount) { }
        @Override public void onMessage(String message) { }
        @Override public void onAppInSession(boolean inSession) { }
        @Override public void onButtonUp() { }
        @Override public void onTopApp(String packageName) { }
        @Override public void onFingers(float[] x, float[] y) { }
    };

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) { }
    }
}
