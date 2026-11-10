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
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streams a display to XDesk Receiver on another device, see receiver/PROTOCOL.md. The display is made by an injector
 * of its own (public and trusted, like AppDisplay), so XDesk uses it like a cable-connected external display: desktop,
 * Android apps, cursor, dashboard and density all work as there, TargetDisplay counts it as real. Its picture goes into
 * a hardware H.264 encoder. Sound is the phone's whole output, recorded by that injector (OutputCapture), so nothing
 * plays on the phone meanwhile; or, by the sound output setting (updateSound), it stays on the phone, e.g. in headphones.
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
    private static final int VERSION = 2;
    private static final int OK = 0, PAIR = 1, BUSY = 2, BAD_VERSION = 3, REFUSED = 4, CHALLENGE = 5;
    private static final int VIDEO_FORMAT = 1, VIDEO_FRAME = 2, AUDIO_FORMAT = 3, AUDIO_DATA = 4, PING = 5, BYE = 6,
        SIZE = 7, KEY_FRAME = 8, ADDRESSES = 11, CLIPBOARD = 12;
    /** Browser → phone only (web display): relative mouse, buttons, wheel steps, Linux key codes, typed text. */
    /** Web only, for ?debug: the browser's 8 bytes come back followed by this phone's clock, in the frames' microseconds. */
    private static final int TIME = 9;
    /** Web only: u8 1 when the phone's touchpad took the pointer, so that the page hides the computer's. */
    private static final int POINTER = 10;
    private static final int CODECS_SENDS_INPUT = 0x80;
    private static final int IN_MOVE = 20, IN_BUTTON = 21, IN_SCROLL = 22, IN_KEY = 23, IN_TEXT = 24, IN_ABS = 25, IN_LAYOUT = 26,
        IN_ANDROID = 27, IN_CHOOSE = 28, FILE_START = 29, FILE_DATA = 30, FILE_END = 31, APPS = 13, INPUT_MODE = 14;
    private static final int CODEC_H264 = 0x68323634;
    private static final long PING_AFTER_MS = 2000;
    private static final String LOOPBACK = "127.0.0.1";
    private static final int CLIPBOARD_POLL_MS = 500, MAX_CLIPBOARD = 256 * 1024;
    private static final int RECONNECT_PAUSE_MS = 300, RECONNECT_CONNECT_MS = 1500;
    private static final int TIMEOUT_MS = 6000, CONNECT_MS = 5000, PAIRING_MS = 120000, INJECTOR_MS = 15000;
    private static final long BYE_WAIT_MS = 1500;
    private static final int MAX_PAYLOAD = 16 << 20;
    /** New injectors after one died, before giving up. */
    private static final int MAX_INJECTOR_RETRIES = 3;
    /** Pixels per second at 1920x1080 and 60 fps; text on a desktop needs a fair rate. */
    private static final int BITRATE_1080P = 12_000_000, MIN_BITRATE = 4_000_000, WIRED_FPS = 120;
    private static final int CALM_SECONDS_TO_RISE = 4;
    /** Over a network the bit rate follows how much of each second went into waiting for the socket. */
    private volatile int fullBitrate, currentBitrate, calmSeconds;
    private volatile boolean receiverBehind;
    private final java.util.concurrent.atomic.AtomicLong writeNanos = new java.util.concurrent.atomic.AtomicLong();

    static final int IDLE = 0, CONNECTING = 1, STREAMING = 2;

    private static WirelessDisplay instance;
    /** For TargetDisplay, which asks from anywhere. */
    private static volatile int displayId = Display.INVALID_DISPLAY;

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private Listener listener;

    private volatile int state = IDLE;
    private volatile String receiverName, address;
    private volatile boolean running, keyFrameWanted, soundCaptured, web;
    /** The receiver sends a keyboard and mouse as a browser does: XDesk for a computer. Said by bit 7 of its codecs. */
    private volatile boolean inputReceiver;
    /** A new picture size the receiver asked for, taken up by the stream. */
    private volatile int[] resizeTo;
    /** Where a computer can be reached besides the address in use, and where it asked to be reached next. */
    private volatile String[] receiverAddresses = new String[0];
    private volatile String preferHost;
    private volatile int receiverPort;
    private volatile String lastClipboard;
    /** The injector of the display being streamed, and its sound recording (null while the sound stays on the phone). */
    private volatile IInjector injector;
    private volatile ParcelFileDescriptor soundPipe;
    private final Object soundLock = new Object();
    private volatile Socket socket;
    private volatile WebSocket webSocket;
    /** The browser has been told that the phone's touchpad has the pointer. */
    private volatile boolean phonePointer;
    /**
     * A browser's connection is gone (a reload, a network hiccup) but the session goes on for GRACE_MS: frames are dropped,
     * the display and the apps on it stay, and the same browser takes up where it was (resumeWeb). Under writeLock.
     */
    private volatile boolean webLost;
    private volatile long graceUntil;
    private volatile String webClient;
    private volatile int streamWidth, streamHeight;
    /** The encoder's codec config: it comes once, and a browser that returns needs it. */
    private volatile byte[] lastConfig;
    private static final long GRACE_MS = 8000;
    private volatile InputSink webInput;
    /** Why the stream ended for good, for a browser (see finalMessage); null for a lost connection. */
    private volatile String endReason;
    /** Key frames at most once a second: a browser behind asks with every frame, and IDR frames make it worse. */
    private long lastSyncRequest;
    private DataOutputStream out;
    private final Object writeLock = new Object();
    private volatile long lastWrite;
    private final LinkedBlockingQueue<String> codes = new LinkedBlockingQueue<>();
    private Thread worker;
    /** A start() waiting for the previous session to end; stop() or a newer start() replaces it. */
    private volatile Thread starting;
    private Thread byeThread;

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

    /**
     * The same sound output setting as for a cable (AudioRoute): system default follows the display, here the receiver;
     * an output of the phone that is there (headphones, the speaker...) keeps the sound on the phone.
     */
    private boolean soundToReceiver() {
        return !AudioRoute.get(context).choosesPresentOutput();
    }

    /** After the choice or the phone's outputs changed. Also while streaming: the recording starts or stops. */
    void updateSound() {
        if (injector == null) return;
        // captureOutput is a Binder call that starts a recording, not for the main thread
        new Thread(() -> {
            synchronized (soundLock) {
                IInjector i = injector;
                if (i == null || state != STREAMING) return;
                boolean on = soundToReceiver();
                if (on && soundPipe == null) captureSound(i);
                else if (!on && soundPipe != null) stopSound();
            }
        }, "xdesk-wireless-sound-switch").start();
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
    /**
     * Main thread. Also while streaming to another receiver: the state of one session (socket, output, display) is
     * shared, so the new one only begins once the old one has ended.
     */
    void start(final String host, final int port, final String name) {
        stop();
        final Thread previous = worker, bye = byeThread;
        final Socket oldSocket = socket;
        if (name == null) prefs.edit().putString("last_address", host).apply();
        final String shownName = name != null ? name : host;
        receiverName = shownName;
        setState(CONNECTING);
        context.startForegroundService(new Intent(context, DesktopService.class)
            .putExtra(DesktopService.EXTRA_TITLE, context.getString(R.string.xd_wireless_title, shownName)));
        Thread t = new Thread(() -> {
            finish(previous, bye, oldSocket);
            if (starting != Thread.currentThread()) {
                // stop() came first; a newer start() has its own state
                if (starting == null && !running) setState(IDLE);
                return;
            }
            starting = null;
            address = host;
            receiverPort = port;
            web = false;
            inputReceiver = false;
            resizeTo = null;
            endReason = null;
            receiverName = shownName;
            codes.clear();
            running = true;
            run(host, port);
        }, "xdesk-wireless");
        starting = t;
        worker = t;
        t.start();
    }

    /** Waits for an ended session's threads. Its BYE may hang on a dead network, so the socket gets closed after a while. */
    private static void finish(Thread previous, Thread bye, Socket socket) {
        try {
            if (bye != null) bye.join(BYE_WAIT_MS);
            closeQuietly(socket);
            if (previous != null) previous.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Main thread. */
    void stop() {
        starting = null;
        if (!running) return;
        running = false;
        codes.offer("");
        final Socket s = socket;
        final WebSocket w = webSocket;
        // Off the main thread: a write may block on a slow network
        byeThread = new Thread(() -> {
            try {
                writeMessage(BYE, new byte[0], 0, 0);
            } catch (IOException ignored) { }
            if (w != null) w.close();
            closeQuietly(s);
        }, "xdesk-wireless-bye");
        byeThread.start();
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

    /**
     * A message for an ending that trying again would not help (no injector, no display): a browser is told so and
     * stops reconnecting, rather than starting the same failure again every few seconds.
     */
    private void finalMessage(final int id, final Object... args) {
        endReason = context.getString(id, args);
        message(id, args);
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
        } catch (RuntimeException e) {
            // The encoder or the display refused this size, or the like: ends this stream, not XDesk
            Log.w(TAG, "stream failed", e);
            finalMessage(R.string.xd_wireless_no_display, String.valueOf(e.getMessage()));
        } finally {
            ended(s);
        }
    }

    /** After the handshake, for a receiver and for a browser alike: the stream until it ends. */
    private void session(DataInputStream in, int width, int height, int dpi) throws IOException {
        startReader(in);
        startPings();
        startClipboardSync();
        int retries = 0;
        while (running) {
            boolean injectorDied = stream(width, height, dpi);
            if (!injectorDied || !running) break;
            if (++retries > MAX_INJECTOR_RETRIES) {
                finalMessage(R.string.xd_wireless_injector_lost);
                break;
            }
            Log.i(TAG, "injector died, new one, try " + retries);
            SystemClock.sleep(1000);
        }
    }

    private void ended(Socket s) {
        running = false;
        WebSocket w = webSocket;
        if (w != null) {
            String reason = endReason;
            if (reason != null) {
                try {
                    w.sendText("{\"status\":\"ended\",\"reason\":" + org.json.JSONObject.quote(reason) + "}");
                } catch (IOException ignored) { }
            }
            w.close();
        }
        closeQuietly(s);
        closeQuietly(socket);   // a browser that returned is on another one
        socket = null;
        webSocket = null;
        webLost = false;
        webClient = null;
        displayId = Display.INVALID_DISPLAY;
        soundCaptured = false;
        // A drag or a held Ctrl from the browser must not stay pressed on the desktop
        if (web || inputReceiver) ui.post(this::releaseWebInput);
        WebServer.get(context).sessionEnded();
        // A start() waiting for this session goes on from here, in CONNECTING
        if (starting == null) setState(IDLE);
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
    boolean startWeb(final Socket s, final WebSocket ws, String id, String name, final int width, final int height, String okJson) {
        synchronized (writeLock) {
            if (canResume(id, width, height)) return resumeWeb(s, ws, okJson);
        }
        // Android 12+ refuses a foreground service from the background (ForegroundServiceStartNotAllowedException).
        // WebServer keeps the service in the foreground while the web display is on, so this is only an update; if it
        // fails anyway, nothing has been set yet and the browser is told.
        try {
            context.startForegroundService(new Intent(context, DesktopService.class)
                .putExtra(DesktopService.EXTRA_TITLE, context.getString(R.string.xd_wireless_title, name)));
        } catch (RuntimeException e) {
            Log.w(TAG, "web: no foreground service", e);
            return false;
        }
        try {
            ws.sendText(okJson);
        } catch (IOException e) {
            return false;
        }
        running = true;
        web = true;
        phonePointer = false;
        webLost = false;
        webClient = id;
        endReason = null;
        address = null;
        receiverName = name;
        codes.clear();
        setState(CONNECTING);
        worker = new Thread(() -> {
            try {
                socket = s;
                webSocket = ws;
                s.setTcpNoDelay(true);
                s.setSoTimeout(TIMEOUT_MS);
                out = new DataOutputStream(ws.output());
                session(new DataInputStream(ws.input()), width, height, 0);
            } catch (NoInjector e) {
                finalMessage(R.string.xd_wireless_need_injector);
            } catch (IOException e) {
                Log.i(TAG, "web ended: " + e);
                if (running) message(R.string.xd_wireless_lost, receiverName);
            } catch (RuntimeException e) {
                Log.w(TAG, "web stream failed", e);
                finalMessage(R.string.xd_wireless_no_display, String.valueOf(e.getMessage()));
            } finally {
                ended(s);
            }
        }, "xdesk-web-display");
        worker.start();
        return true;
    }

    // What the browser holds down, main thread: released when the session ends (releaseWebInput)
    private final java.util.Set<Integer> webButtons = java.util.concurrent.ConcurrentHashMap.newKeySet(), webKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The phone's touchpad moved. Main thread: the write goes elsewhere. */
    void phonePointerMoved() {
        if (!web || state != STREAMING || phonePointer) return;
        phonePointer = true;
        new Thread(() -> {
            try {
                writeMessage(POINTER, new byte[]{1}, 0, 1);
            } catch (IOException ignored) { }
        }, "xdesk-web-pointer").start();
    }

    private void releaseWebInput() {
        releaseWebInput(true);
    }

    /** @param pointerToo false while the browser may come back: its pointer is still the one in use */
    private void releaseWebInput(boolean pointerToo) {
        InputSink sink = webInput;
        if (sink != null) {
            for (int b : webButtons) sink.button(b, false);
            for (int k : webKeys) sink.key(k, false);
            if (pointerToo) sink.pointerGone();
        }
        webButtons.clear();
        webKeys.clear();
    }

    /** Browser → phone input, inside the same framing; only a browser sends these. */
    private void webInput(int type, byte[] b) {
        final InputSink sink = webInput;
        if (sink == null || !(web || inputReceiver)) return;
        final ByteBuffer p = ByteBuffer.wrap(b);
        Runnable r;
        switch (type) {
            case IN_MOVE:
                if (b.length < 4) return;
                final int dx = p.getShort(), dy = p.getShort();
                r = () -> sink.move(dx, dy);
                break;
            case IN_ABS:
                if (b.length < 4) return;
                phonePointer = false;
                final float fx = (p.getShort() & 0xffff) / 65535f, fy = (p.getShort() & 0xffff) / 65535f;
                r = () -> sink.moveToFraction(fx, fy);
                break;
            case IN_BUTTON:
                if (b.length < 2) return;
                final int button = b[0] & 0xff;
                final boolean down = b[1] != 0;
                if (button < 1 || button > 3) return;
                r = () -> {
                    if (down) webButtons.add(button); else webButtons.remove(button);
                    sink.button(button, down);
                };
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
                r = () -> {
                    if (pressed) webKeys.add(scan); else webKeys.remove(scan);
                    sink.key(scan, pressed);
                };
                break;
            case IN_TEXT:
                if (b.length == 0 || b.length > 4096) return;
                final String text = new String(b, StandardCharsets.UTF_8);
                r = () -> sink.text(text);
                break;
            case IN_LAYOUT:
                if (b.length == 0 || b.length > 8) return;
                final String layout = new String(b, StandardCharsets.US_ASCII);
                r = () -> sink.browserLayout(layout);
                break;
            case IN_ANDROID:
                if (b.length != 1) return;
                final int androidButton = b[0];
                r = () -> sink.androidButton(androidButton);
                break;
            case IN_CHOOSE:
                final String chosen = new String(b, StandardCharsets.UTF_8);
                r = () -> sink.chooseApp(chosen);
                break;
            default:
                return;
        }
        boolean plainInput = type == IN_MOVE || type == IN_ABS || type == IN_BUTTON || type == IN_SCROLL || type == IN_KEY || type == IN_TEXT;
        if (plainInput && inputReceiver && sink.anyThread()) r.run();
        else ui.post(r);
    }

    static final class OutgoingFile {
        final String name;
        final ParcelFileDescriptor file;

        OutgoingFile(String name, ParcelFileDescriptor file) {
            this.name = name;
            this.file = file;
        }
    }

    private static final int FILE_CHUNK = 64 * 1024;
    private final java.util.concurrent.ExecutorService fileSender = java.util.concurrent.Executors.newSingleThreadExecutor();

    boolean canSendFiles() {
        return inputReceiver && state == STREAMING && !webLost;
    }

    /** To the computer's Downloads, one after the other. Takes over the files. */
    void sendFiles(java.util.List<OutgoingFile> files) {
        for (OutgoingFile f : files) fileSender.execute(() -> sendFile(f));
    }

    private void sendFile(OutgoingFile f) {
        Socket session = socket;
        try (java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(f.file)) {
            byte[] name = f.name.getBytes(StandardCharsets.UTF_8);
            if (!canSendFiles()) throw new IOException("not connected");
            writeMessage(FILE_START, name, 0, name.length);
            byte[] chunk = new byte[FILE_CHUNK];
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (!canSendFiles() || socket != session) throw new IOException("connection changed");
                writeMessage(FILE_DATA, chunk, 0, n);
            }
            writeMessage(FILE_END, chunk, 0, 0);
            message(R.string.xd_share_sent, f.name);
        } catch (IOException e) {
            Log.i(TAG, "file not sent: " + e);
            try {
                // An empty name: the computer forgets what it has of this file
                writeMessage(FILE_START, new byte[0], 0, 0);
            } catch (IOException ignored) { }
            message(R.string.xd_share_failed, f.name);
        }
    }

    /** The apps a computer can switch to, as lines of key, tab, label. */
    void sendApps(String lines) {
        new Thread(() -> {
            try {
                byte[] b = lines.getBytes(StandardCharsets.UTF_8);
                writeMessage(APPS, b, 0, b.length);
            } catch (IOException ignored) { }
        }, "xdesk-wireless-apps").start();
    }

    // A file dropped on a computer's window, into Downloads. On the reader thread, in order.
    private android.net.Uri fileUri;
    private java.io.OutputStream fileOut;
    private String fileName;

    private void fileStart(String name) {
        fileEnd(false);
        if (name.isEmpty()) return;
        fileName = name.replace('/', '_');
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
        v.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
        android.content.ContentResolver r = context.getContentResolver();
        try {
            fileUri = r.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            fileOut = fileUri != null ? r.openOutputStream(fileUri) : null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "file", e);
            fileOut = null;
        }
    }

    private void fileData(byte[] b) {
        if (fileOut == null) return;
        try {
            fileOut.write(b);
        } catch (IOException e) {
            Log.w(TAG, "file", e);
            fileEnd(false);
        }
    }

    private void fileEnd(boolean complete) {
        android.content.ContentResolver r = context.getContentResolver();
        if (fileOut != null) {
            try {
                fileOut.close();
            } catch (IOException e) {
                complete = false;
            }
        }
        if (fileUri != null) {
            if (complete) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                r.update(fileUri, v, null, null);
                message(R.string.xd_file_saved, fileName);
            } else {
                r.delete(fileUri, null, null);
            }
        }
        fileOut = null;
        fileUri = null;
    }

    /** Returns width, height and density, or null when the receiver said no or the user gave up. */
    private int[] handshake(DataInputStream in) throws IOException {
        final String phoneNonce = randomHex(32);
        synchronized (writeLock) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            writeString(phoneId());
            writeString(phoneName());
            writeString(phoneNonce);
            out.flush();
        }
        boolean wrong = false;
        while (true) {
            int status = in.readUnsignedByte();
            String id = readString(in), name = readString(in);
            if (!name.isEmpty()) receiverName = name;
            if (status == CHALLENGE) {
                // The id is only a claim: anyone can announce it. The token is used, never sent, and only for a
                // receiver that has shown it holds it; otherwise it is a new pairing, and the code decides
                String nonce = readString(in), theirs = readString(in);
                String token = prefs.getString("token_" + id, "");
                String answer = "";
                if (!token.isEmpty() && !theirs.isEmpty() && java.security.MessageDigest.isEqual(
                        proof(token, "receiver", id, phoneNonce, nonce).getBytes(StandardCharsets.UTF_8),
                        theirs.getBytes(StandardCharsets.UTF_8))) {
                    answer = proof(token, "phone", id, phoneNonce, nonce);
                }
                synchronized (writeLock) {
                    writeString(answer);
                    out.flush();
                }
                continue;
            }
            if (status == OK) {
                String token = readString(in);
                if (!token.isEmpty()) prefs.edit().putString("token_" + id, token).apply();
                int w = in.readUnsignedShort(), h = in.readUnsignedShort(), dpi = in.readUnsignedShort();
                in.readUnsignedShort();  // refresh rate: the encoder runs at up to 60 anyway
                inputReceiver = (in.readUnsignedByte() & CODECS_SENDS_INPUT) != 0;   // H.264 is always there
                if (inputReceiver) prefs.edit().putString("auto_receiver", id).apply();
                Log.i(TAG, "receiver " + receiverName + " wants " + w + "x" + h + " at " + dpi + " dpi");
                // It comes from the network: even, and a size encoders take, as for a browser
                int[] size = WebServer.fit(w, h, wired());
                return new int[]{size[0], size[1], dpi};
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

    /** See receiver/PROTOCOL.md, Handshake. */
    private String proof(String token, String label, String receiverId, String phoneNonce, String receiverNonce) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal((label + "\n" + phoneId() + "\n" + receiverId + "\n" + phoneNonce + "\n" + receiverNonce)
                .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        return hex(b);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
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
            if (webLost) return;
            DataOutputStream o = out;
            if (o == null) throw new IOException("not connected");
            try {
                long began = System.nanoTime();
                o.writeByte(type);
                o.writeInt(head.length + size);
                o.write(head);
                if (size > 0) {
                    byte[] b = new byte[size];
                    data.get(b);
                    o.write(b);
                }
                o.flush();
                writeNanos.addAndGet(System.nanoTime() - began);
            } catch (IOException e) {
                if (!connectionLost()) throw e;
                closeQuietly(socket);
                return;
            }
            lastWrite = SystemClock.uptimeMillis();
        }
    }

    /** Under writeLock. True if this is a browser's session, which now waits for the browser to return. */
    private boolean connectionLost() {
        if (!(web || inputReceiver) || !running) return false;
        if (!webLost) {
            webLost = true;
            graceUntil = SystemClock.uptimeMillis() + GRACE_MS;
            Log.i(TAG, "browser gone, keeping the display for " + GRACE_MS + " ms");
            ui.post(() -> releaseWebInput(false));
            if (inputReceiver) {
                reconnectReceiver();
                notifyChanged();
            }
        }
        return true;
    }

    /** The same browser, back in time, and the display still fits it. */
    private boolean canResume(String id, int width, int height) {
        return web && running && webLost && id.equals(webClient) && width == streamWidth && height == streamHeight
            && SystemClock.uptimeMillis() <= graceUntil;
    }

    /** Whether a browser with this id and picture size would take over the waiting session rather than be busy. */
    boolean webResumable(String id, int width, int height) {
        synchronized (writeLock) {
            return canResume(id, width, height);
        }
    }

    /** Under writeLock. Gives the session to the browser's new connection: the stream starts over from a key frame. */
    private boolean resumeWeb(Socket s, WebSocket ws, String okJson) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(TIMEOUT_MS);
            ws.sendText(okJson);
            socket = s;
            webSocket = ws;
            out = new DataOutputStream(ws.output());
            webLost = false;
            restartStream();
            startReader(new DataInputStream(ws.input()));
            Log.i(TAG, "browser back, same display");
            return true;
        } catch (IOException e) {
            Log.i(TAG, "resume: " + e);
            return false;
        }
    }

    /** Under writeLock, on a new connection: the stream starts over from a key frame. */
    private void restartStream() throws IOException {
        sentInputMode = -1;
        ByteBuffer format = ByteBuffer.allocate(8).putInt(CODEC_H264).putShort((short) streamWidth).putShort((short) streamHeight);
        writeMessage(VIDEO_FORMAT, format.array(), 0, 8);
        byte[] config = lastConfig;
        if (config != null) {
            byte[] head = ByteBuffer.allocate(9).putLong(0).put((byte) 1).array();
            writeMessage(VIDEO_FRAME, head, ByteBuffer.wrap(config), config.length);
        }
        lastSyncRequest = 0;
        keyFrameWanted = true;
    }

    boolean clipboardSyncOn() {
        return prefs.getBoolean("clipboard_sync", true);
    }

    void setClipboardSync(boolean on) {
        prefs.edit().putBoolean("clipboard_sync", on).apply();
    }

    /** A computer that was connected to and may be again without asking. Null if there is none or it was disconnected by hand. */
    String autoReceiverId() {
        return prefs.getString("auto_receiver", null);
    }

    void forgetAutoReceiver() {
        prefs.edit().remove("auto_receiver").apply();
    }

    /** The phone's clipboard to a computer, when it changes: what it had when the session began stays. */
    /** Whether the computer is looking at the Termux desktop, which takes any text, or at Android apps. */
    private volatile boolean desktopShown;
    private volatile int sentInputMode = -1;

    void setDesktopShown(boolean on) {
        if (on == desktopShown) return;
        desktopShown = on;
        if (on) showComputerClipboard();
    }

    private void sendInputMode() {
        int mode = desktopShown ? 1 : 0;
        if (mode == sentInputMode || webLost) return;
        try {
            writeMessage(INPUT_MODE, new byte[]{(byte) mode}, 0, 1);
            sentInputMode = mode;
        } catch (IOException e) {
            Log.i(TAG, "input mode: " + e);
        }
    }

    private volatile String computerClipboard;

    private void showComputerClipboard() {
        final String text = computerClipboard;
        if (text == null) return;
        ui.post(() -> {
            LorieView v = DesktopInput.view();
            if (v != null) v.setComputerClipboard(text);
        });
    }

    private void startClipboardSync() {
        if (!inputReceiver) return;
        new Thread(() -> {
            boolean first = true;
            while (running && inputReceiver) {
                SystemClock.sleep(CLIPBOARD_POLL_MS);
                sendInputMode();
                IInjector i = injector;
                if (i == null || webLost || !clipboardSyncOn()) continue;
                try {
                    String text = i.clipboardText();
                    if (first) {
                        lastClipboard = text;
                        first = false;
                    } else if (text != null && !text.equals(lastClipboard)) {
                        lastClipboard = text;
                        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                        if (bytes.length <= MAX_CLIPBOARD) writeMessage(CLIPBOARD, bytes, 0, bytes.length);
                    }
                } catch (RemoteException | IOException e) {
                    Log.i(TAG, "clipboard: " + e);
                }
            }
        }, "xdesk-clipboard").start();
    }

    private void notifyChanged() {
        ui.post(() -> {
            if (listener != null) listener.onWirelessChanged();
        });
    }

    /** The computer or browser whose keyboard and mouse this stream takes, null without one. */
    String inputSource() {
        return (web || inputReceiver) && state == STREAMING ? receiverName : null;
    }

    /** A computer's connection is gone and is being looked for. */
    boolean reconnecting() {
        return inputReceiver && webLost;
    }

    private boolean wired() {
        return LOOPBACK.equals(address);
    }

    /** Main thread. A computer found a cable: to it, keeping the session if it is already streaming there another way. */
    void connectFromComputer(String host, int port, String name) {
        if (running && inputReceiver) {
            if (host.equals(address)) return;
            preferHost = host;
            closeQuietly(socket);
            return;
        }
        if (running && host.equals(address)) return;
        start(host, port, name);
    }

    /** A computer's connection is gone, as when a cable is pulled: the session waits and looks for another way there. */
    private void reconnectReceiver() {
        new Thread(() -> {
            while (running && webLost && SystemClock.uptimeMillis() <= graceUntil) {
                ArrayList<String> hosts = new ArrayList<>();
                String preferred = preferHost;
                if (preferred != null) hosts.add(preferred);
                if (address != null && !hosts.contains(address)) hosts.add(address);
                for (String a : receiverAddresses) if (!a.isEmpty() && !hosts.contains(a)) hosts.add(a);
                for (String host : hosts) {
                    if (!running || !webLost) return;
                    if (backTo(host)) return;
                }
                SystemClock.sleep(RECONNECT_PAUSE_MS);
            }
        }, "xdesk-wireless-reconnect").start();
    }

    private boolean backTo(String host) {
        Socket s = new Socket();
        String before = address;
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, receiverPort), RECONNECT_CONNECT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            synchronized (writeLock) {
                address = host;
                socket = s;
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
            }
            int[] size = handshake(in);
            if (size == null) throw new IOException("refused");
            synchronized (writeLock) {
                webLost = false;
                preferHost = null;
                restartStream();
                startReader(in);
            }
            // Also at the same size: a cable and a network get different encoders
            resizeTo = new int[]{size[0], size[1]};
            Log.i(TAG, "receiver back on " + host);
            notifyChanged();
            return true;
        } catch (IOException e) {
            Log.i(TAG, "not back on " + host + ": " + e);
            address = before;
            closeQuietly(s);
            return false;
        }
    }

    private void writeMessage(int type, byte[] payload, int offset, int size) throws IOException {
        synchronized (writeLock) {
            if (webLost) return;
            DataOutputStream o = out;
            if (o == null) throw new IOException("not connected");
            try {
                o.writeByte(type);
                o.writeInt(size);
                o.write(payload, offset, size);
                o.flush();
            } catch (IOException e) {
                if (!connectionLost()) throw e;
                closeQuietly(socket);
                return;
            }
            lastWrite = SystemClock.uptimeMillis();
        }
    }

    private void startReader(final DataInputStream in) {
        final Socket mine = socket;
        new Thread(() -> {
            try {
                while (running) {
                    int type = in.readUnsignedByte();
                    int size = in.readInt();
                    if (size < 0 || size > MAX_PAYLOAD) throw new IOException("bad size " + size);
                    byte[] b = new byte[size];
                    in.readFully(b);
                    if (type == FILE_START && inputReceiver) fileStart(new String(b, StandardCharsets.UTF_8));
                    else if (type == FILE_DATA && inputReceiver) fileData(b);
                    else if (type == FILE_END && inputReceiver) fileEnd(true);
                    else if (type >= IN_MOVE) webInput(type, b);
                    else if (type == KEY_FRAME) {
                        keyFrameWanted = true;
                        receiverBehind = true;
                    }
                    else if (type == TIME && (web || inputReceiver) && size == 8) {
                        ByteBuffer reply = ByteBuffer.allocate(16).put(b).putLong(System.nanoTime() / 1000);
                        writeMessage(TIME, reply.array(), 0, 16);
                    }
                    else if (type == BYE) {
                        // Ended from the computer on purpose: not to be connected back by itself
                        if (inputReceiver) forgetAutoReceiver();
                        message(R.string.xd_wireless_closed, receiverName);
                        running = false;
                        break;
                    } else if (type == CLIPBOARD && size <= MAX_CLIPBOARD && clipboardSyncOn()) {
                        String text = new String(b, StandardCharsets.UTF_8);
                        lastClipboard = text;
                        IInjector i = injector;
                        if (i != null) {
                            try {
                                i.setClipboardText(text);
                            } catch (RemoteException ignored) { }
                        }
                        computerClipboard = text;
                        if (desktopShown) showComputerClipboard();
                    } else if (type == ADDRESSES) {
                        receiverAddresses = new String(b, StandardCharsets.UTF_8).split(",");
                    } else if (type == SIZE && size >= 4) {
                        int[] wanted = WebServer.fit(ByteBuffer.wrap(b).getShort() & 0xffff, ByteBuffer.wrap(b, 2, 2).getShort() & 0xffff, wired());
                        if (wanted[0] != streamWidth || wanted[1] != streamHeight) resizeTo = wanted;
                    }
                }
            } catch (SocketTimeoutException e) {
                if (running) Log.i(TAG, "receiver silent");
            } catch (IOException e) {
                if (running) Log.i(TAG, "reader: " + e);
            }
            fileEnd(false);
            // The worker must not wait for its next write: with the phone locked the display may make no more frames.
            // Only this session: the next one may already have started if the worker got here first
            closeQuietly(mine);
            synchronized (writeLock) {
                if (socket == mine && !connectionLost()) running = false;
            }
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
        streamWidth = width;
        streamHeight = height;
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
        try {
            i.asBinder().linkToDeath(death, 0);
            // Linked to XDesk: if XDesk dies, the display and the recording end with it
            i.attach(NO_EVENTS, context.getPackageName());
            i.setEnabled(false);
            boolean encoderWired = wired();
            encoder = encoder(width, height, encoderWired);
            input = encoder.createInputSurface();
            encoder.start();
            // As an external monitor of that width: 2560 wide is about 213 dpi, 1920 is 160
            int dpi = Math.max(120, Math.round(width * 213f / 2560f));
            int id;
            try {
                id = i.createVirtualDisplay("XDesk wireless", width, height, dpi, input, encoderWired ? WIRED_FPS : 0);
            } catch (IllegalStateException e) {
                finalMessage(R.string.xd_wireless_no_display, ShellText.localize(context, String.valueOf(e.getMessage())));
                running = false;
                return false;
            }
            displayId = id;
            Log.i(TAG, "display " + id + ", " + width + "x" + height + " " + dpi + " dpi, receiver " + receiverDpi + " dpi");
            synchronized (soundLock) {
                injector = i;
                if (soundToReceiver()) captureSound(i);
                setState(STREAMING);
            }
            final int shown = id;
            ui.post(() -> {
                if (listener != null) listener.onWirelessDisplay(shown);
            });
            ByteBuffer format = ByteBuffer.allocate(8).putInt(CODEC_H264).putShort((short) width).putShort((short) height);
            writeMessage(VIDEO_FORMAT, format.array(), 0, 8);
            while (true) {
                pump(encoder, died, id);
                int[] size = resizeTo;
                resizeTo = null;
                if (size == null || !running || died[0]) return died[0];
                if (size[0] == width && size[1] == height && encoderWired == wired()) continue;
                // The display stays, with its apps: only the size changes, and the encoder that has it
                width = size[0];
                height = size[1];
                encoderWired = wired();
                MediaCodec resized = encoder(width, height, encoderWired);
                Surface resizedInput = resized.createInputSurface();
                resized.start();
                i.resizeVirtualDisplay(id, width, height, dpi, resizedInput);
                try {
                    encoder.stop();
                } catch (IllegalStateException ignored) { }
                encoder.release();
                input.release();
                encoder = resized;
                input = resizedInput;
                streamWidth = width;
                streamHeight = height;
                Log.i(TAG, "display " + id + " now " + width + "x" + height);
                format = ByteBuffer.allocate(8).putInt(CODEC_H264).putShort((short) width).putShort((short) height);
                writeMessage(VIDEO_FORMAT, format.array(), 0, 8);
            }
        } catch (RemoteException e) {
            // The injector died while being set up
            return true;
        } finally {
            displayId = Display.INVALID_DISPLAY;
            synchronized (soundLock) {
                injector = null;
                if (soundPipe != null) stopSound();
            }
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

    private long lastBitrateCheck;

    private void adjustBitrate(MediaCodec encoder) {
        double busy = writeNanos.getAndSet(0) / 1e9;
        boolean behind = receiverBehind;
        receiverBehind = false;
        if (wired() || webLost) return;
        int next = currentBitrate;
        if (behind || busy > 0.45) {
            next = Math.max(MIN_BITRATE, currentBitrate * 7 / 10);
            calmSeconds = 0;
        } else if (busy < 0.15 && ++calmSeconds >= CALM_SECONDS_TO_RISE) {
            next = Math.min(fullBitrate, currentBitrate * 115 / 100);
            calmSeconds = 0;
        }
        if (next == currentBitrate) return;
        currentBitrate = next;
        Log.i(TAG, "bit rate " + next);
        Bundle b = new Bundle();
        b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next);
        encoder.setParameters(b);
    }

    private void pump(MediaCodec encoder, boolean[] died, int id) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        android.hardware.display.DisplayManager dm = context.getSystemService(android.hardware.display.DisplayManager.class);
        long checked = SystemClock.uptimeMillis();
        while (running && !died[0] && resizeTo == null) {
            // The injector may be alive with its display gone, as when its session was ended: treat it as dead
            if (SystemClock.uptimeMillis() - checked > 1000) {
                checked = SystemClock.uptimeMillis();
                if (dm.getDisplay(id) == null) {
                    Log.i(TAG, "display " + id + " is gone");
                    died[0] = true;
                    break;
                }
            }
            if (SystemClock.uptimeMillis() - lastBitrateCheck >= 1000) {
                lastBitrateCheck = SystemClock.uptimeMillis();
                adjustBitrate(encoder);
            }
            if (webLost && SystemClock.uptimeMillis() > graceUntil) {
                Log.i(TAG, "browser did not come back");
                running = false;
                break;
            }
            if (keyFrameWanted && SystemClock.uptimeMillis() - lastSyncRequest >= 1000) {
                keyFrameWanted = false;
                lastSyncRequest = SystemClock.uptimeMillis();
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
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        byte[] config = new byte[info.size];
                        data.duplicate().get(config);
                        lastConfig = config;
                    }
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
    private MediaCodec encoder(int w, int h, boolean wired) throws IOException {
        if (wired) {
            try {
                return encoderAt(w, h, WIRED_FPS);
            } catch (IllegalStateException | IllegalArgumentException e) {
                Log.i(TAG, "no " + WIRED_FPS + " fps at " + w + "x" + h + ": " + e);
            }
        }
        return encoderAt(w, h, 60);
    }

    private MediaCodec encoderAt(int w, int h, int fps) throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        long rate = (long) BITRATE_1080P * w * h / (1920L * 1080L);
        fullBitrate = (int) Math.max(MIN_BITRATE, rate);
        currentBitrate = fullBitrate;
        calmSeconds = 0;
        f.setInteger(MediaFormat.KEY_BIT_RATE, fullBitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        // The receiver would otherwise keep an old picture until something moves
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
        f.setFloat("max-fps-to-encoder", fps);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        f.setInteger(MediaFormat.KEY_LATENCY, 1);
        // B-frames make the decoder hold frames back to reorder them
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        // Qualcomm's low latency mode; other vendors ignore the key
        f.setInteger("vendor.qti-ext-enc-low-latency.enable", 1);
        MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (IllegalStateException | IllegalArgumentException e) {
            c.release();
            throw e;
        }
        return c;
    }

    /** The phone's sound output into AUDIO_DATA, sets soundPipe; under soundLock. Without it the picture still goes, and sound stays on the phone. */
    private void captureSound(IInjector i) {
        final ParcelFileDescriptor pfd;
        try {
            pfd = i.captureOutput();
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "no sound capture", e);
            message(R.string.xd_wireless_no_sound, ShellText.localize(context, String.valueOf(e.getMessage())));
            return;
        }
        soundPipe = pfd;
        soundCaptured = true;
        new Thread(() -> {
            byte[] buf = new byte[OutputCapture.RATE * 4 / 50];
            try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                // Again after the sound came back from the phone: the receiver starts a new player
                ByteBuffer format = ByteBuffer.allocate(6).putInt(OutputCapture.RATE).put((byte) 2).put((byte) 1);
                writeMessage(AUDIO_FORMAT, format.array(), 0, 6);
                while (running && soundPipe == pfd) {
                    int n = in.read(buf);
                    if (n < 0 || soundPipe != pfd) break;
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
    }

    /** Under soundLock. Closing XDesk's end makes the injector stop recording, and the system plays on the phone again. */
    private void stopSound() {
        ParcelFileDescriptor pfd = soundPipe;
        soundPipe = null;
        soundCaptured = false;
        if (pfd != null) closeQuietly(pfd);
        ui.post(() -> {
            if (listener != null) listener.onWirelessChanged();
        });
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
