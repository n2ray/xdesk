package io.github.n2ray.xdesk.receiver;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** One connection from a phone, see PROTOCOL.md. Its thread reads; a second one sends PINGs and replies. */
final class Session extends Thread implements Video.Listener {
    // WELCOME status
    static final int OK = 0, PAIR = 1, BUSY = 2, VERSION = 3, REFUSED = 4;
    // Message types
    static final int VIDEO_FORMAT = 1, VIDEO_FRAME = 2, AUDIO_FORMAT = 3, AUDIO_DATA = 4, PING = 5, BYE = 6,
            SIZE = 7, KEY_FRAME = 8;
    private static final int MAX_STRING = 1024, MAX_PAYLOAD = 16 << 20;
    private static final byte[] MAGIC = { 'X', 'D', 'S', 'K' };
    private static final int HELLO_TIMEOUT_MS = 10_000;
    // There are no PINGs before OK; this long for the user to type the code
    private static final int CODE_TIMEOUT_MS = 120_000;
    private static final int PING_AFTER_MS = 2_000, LOST_AFTER_MS = 6_000;
    private static final long KEY_FRAME_EVERY_MS = 1_000;

    private final Receiver r;
    private final Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final LinkedBlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
    private volatile long lastSent;
    private volatile boolean streaming, closed;
    private long lastKeyRequest = -KEY_FRAME_EVERY_MS;
    volatile String phoneId = "", phoneName = "";

    Session(Receiver r, Socket socket) {
        super("session");
        this.r = r;
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(HELLO_TIMEOUT_MS);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
            out = new BufferedOutputStream(socket.getOutputStream(), 1 << 12);
            if (handshake()) stream();
        } catch (IOException e) {
            if (!closed) Log.i(MainActivity.TAG, "connection ended: " + e);
        } finally {
            closed = true;
            Receiver.closeQuietly(socket);
            r.ended(this);
        }
    }

    private boolean handshake() throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            Log.i(MainActivity.TAG, "not an XDesk phone");
            return false;
        }
        // Answered before reading on: a later version's HELLO may go on differently
        if (in.readUnsignedByte() != 1) {
            welcome(VERSION, null);
            return false;
        }
        phoneId = readString();
        phoneName = readString();
        String token = readString();
        if (!r.claim(this)) {
            welcome(BUSY, null);
            return false;
        }
        String newToken = "";
        if (!r.store.tokenMatches(phoneId, token)) {
            socket.setSoTimeout(CODE_TIMEOUT_MS);
            boolean retry = false;
            while (true) {
                if (r.isActive(this)) r.ui.pairing(phoneName, r.pairingCode(), retry);
                welcome(PAIR, null);
                int result = r.checkCode(readString());
                if (result == Receiver.CODE_OK) break;
                if (result == Receiver.CODE_REFUSED) {
                    Log.i(MainActivity.TAG, "too many wrong codes");
                    welcome(REFUSED, null);
                    return false;
                }
                retry = true;
            }
            newToken = r.store.newToken(phoneId);
        }
        if (r.isActive(this)) r.ui.connecting(phoneName);
        welcome(OK, newToken);
        streaming = true;
        return true;
    }

    private void welcome(int status, String token) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeByte(status);
        writeString(d, r.store.id());
        writeString(d, r.store.name());
        if (status == OK) {
            Screen s = r.screen();
            writeString(d, token);
            d.writeShort(s.width);
            d.writeShort(s.height);
            d.writeShort(s.density);
            d.writeShort(s.fps);
            d.writeByte(1 | (s.hevc ? 2 : 0));
            Log.i(MainActivity.TAG, "streaming from " + phoneName + ", asking for " + s.width + "x" + s.height
                    + " " + s.density + "dpi " + s.fps + "fps" + (s.hevc ? " h265" : ""));
        }
        write(b.toByteArray());
    }

    private void stream() throws IOException {
        socket.setSoTimeout(LOST_AFTER_MS);
        Thread sender = new Thread(this::sendLoop, "sender");
        sender.start();
        Video video = new Video(r.surface(), this);
        Audio audio = null;
        byte[] buf = new byte[1 << 18], abuf = new byte[1 << 14];
        try {
            while (!closed) {
                int type = in.readUnsignedByte();
                int len = in.readInt();
                if (len < 0 || len > MAX_PAYLOAD) throw new IOException("payload length " + len);
                switch (type) {
                    case VIDEO_FORMAT: {
                        need(len, 8);
                        int codec = in.readInt();
                        int w = in.readUnsignedShort(), h = in.readUnsignedShort();
                        skip(len - 8);
                        Log.i(MainActivity.TAG, "video " + Integer.toHexString(codec) + " " + w + "x" + h);
                        if (r.isActive(this)) r.ui.videoSize(w, h);
                        video.format(codec, w, h);
                        lastKeyRequest = -KEY_FRAME_EVERY_MS;
                        break;
                    }
                    case VIDEO_FRAME: {
                        need(len, 9);
                        long pts = in.readLong();
                        int flags = in.readUnsignedByte();
                        int n = len - 9;
                        if (n > buf.length) buf = new byte[n + n / 4];
                        in.readFully(buf, 0, n);
                        if (video.frame(pts, flags, buf, n)) requestKeyFrame();
                        break;
                    }
                    case AUDIO_FORMAT: {
                        need(len, 6);
                        int rate = in.readInt(), channels = in.readUnsignedByte(), encoding = in.readUnsignedByte();
                        skip(len - 6);
                        if (audio != null) audio.close();
                        audio = Audio.create(rate, channels, encoding);
                        break;
                    }
                    case AUDIO_DATA: {
                        need(len, 8);
                        in.readLong();
                        int n = len - 8;
                        if (n > abuf.length) abuf = new byte[n];
                        in.readFully(abuf, 0, n);
                        if (audio != null) audio.write(abuf, 0, n);
                        break;
                    }
                    case BYE:
                        Log.i(MainActivity.TAG, "phone stopped");
                        return;
                    default:
                        // PING, and types a later version may add
                        skip(len);
                }
            }
        } finally {
            closed = true;
            sender.interrupt();
            video.release();
            if (audio != null) audio.close();
        }
    }

    private void requestKeyFrame() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastKeyRequest < KEY_FRAME_EVERY_MS) return;
        lastKeyRequest = now;
        outbox.offer(message(KEY_FRAME, new byte[0]));
    }

    void sendSize(Screen s) {
        if (!streaming || closed) return;
        byte[] p = new byte[6];
        p[0] = (byte) (s.width >> 8);
        p[1] = (byte) s.width;
        p[2] = (byte) (s.height >> 8);
        p[3] = (byte) s.height;
        p[4] = (byte) (s.density >> 8);
        p[5] = (byte) s.density;
        Log.i(MainActivity.TAG, "screen now " + s.width + "x" + s.height + " " + s.density + "dpi");
        outbox.offer(message(SIZE, p));
    }

    private void sendLoop() {
        byte[] ping = message(PING, new byte[0]);
        try {
            while (!closed) {
                byte[] m = outbox.poll(500, TimeUnit.MILLISECONDS);
                if (m != null) write(m);
                else if (SystemClock.elapsedRealtime() - lastSent >= PING_AFTER_MS) write(ping);
            }
        } catch (InterruptedException e) {
            // Session over
        } catch (IOException e) {
            if (!closed) Log.i(MainActivity.TAG, "send: " + e);
            Receiver.closeQuietly(socket);
        }
    }

    /** Ends the session from another thread; BYE first when the user closed the receiver during a stream */
    void kill(boolean bye) {
        closed = true;
        if (bye && streaming) {
            try {
                if (writeLock.tryLock(300, TimeUnit.MILLISECONDS)) {
                    try {
                        out.write(message(BYE, new byte[0]));
                        out.flush();
                    } finally {
                        writeLock.unlock();
                    }
                }
            } catch (IOException | InterruptedException e) {
                // Closing anyway
            }
        }
        Receiver.closeQuietly(socket);
    }

    @Override
    public void firstFrame() {
        if (!closed && r.isActive(this)) r.ui.streaming();
    }

    @Override
    public void cannotDecode(int width, int height) {
        if (r.isActive(this)) r.ui.cannotDecode(phoneName, width, height);
    }

    private void write(byte[] b) throws IOException {
        writeLock.lock();
        try {
            out.write(b);
            out.flush();
            lastSent = SystemClock.elapsedRealtime();
        } finally {
            writeLock.unlock();
        }
    }

    private static byte[] message(int type, byte[] payload) {
        byte[] m = new byte[5 + payload.length];
        m[0] = (byte) type;
        m[1] = (byte) (payload.length >>> 24);
        m[2] = (byte) (payload.length >>> 16);
        m[3] = (byte) (payload.length >>> 8);
        m[4] = (byte) payload.length;
        System.arraycopy(payload, 0, m, 5, payload.length);
        return m;
    }

    private String readString() throws IOException {
        int len = in.readUnsignedShort();
        if (len > MAX_STRING) throw new IOException("string length " + len);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream d, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        // Only a name could be this long; cut it on a character boundary
        while (b.length > MAX_STRING) {
            s = s.substring(0, s.length() - 1);
            b = s.getBytes(StandardCharsets.UTF_8);
        }
        d.writeShort(b.length);
        d.write(b);
    }

    private static void need(int len, int min) throws IOException {
        if (len < min) throw new IOException("payload too short: " + len);
    }

    private void skip(int n) throws IOException {
        while (n > 0) {
            int k = in.skipBytes(n);
            if (k <= 0) {
                if (in.read() < 0) throw new EOFException();
                k = 1;
            }
            n -= k;
        }
    }
}
