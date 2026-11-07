package com.termux.x11;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The server side of RFC 6455, as much as the web display needs: one connection, binary and text messages, ping, close.
 * Client frames are masked, server frames are not. Messages above MAX_MESSAGE are an error.
 *
 * The web display speaks the wireless display protocol (receiver/PROTOCOL.md) inside binary messages, one message per
 * WebSocket message: {@link #output()} sends what was written since the last flush() as one message, and
 * {@link #input()} reads the payloads of incoming binary messages back to back.
 */
final class WebSocket {
    static final int TEXT = 1, BINARY = 2, CLOSE = 8, PING = 9, PONG = 10;
    private static final int MAX_MESSAGE = 1 << 20;

    private final DataInputStream in;
    private final OutputStream out;
    private final Object sendLock = new Object();
    private volatile boolean closed;
    /** Before pairing: messages are small, and every read ends by an absolute time, pings or not. */
    private volatile int maxMessage = MAX_MESSAGE;
    private volatile java.net.Socket deadlineSocket;
    private volatile long deadline;

    /** A message as received: its opcode (TEXT or BINARY) and payload. */
    static final class Message {
        final int type;
        final byte[] data;
        Message(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
        String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    WebSocket(InputStream in, OutputStream out) {
        this.in = new DataInputStream(in);
        this.out = out;
    }

    /** Largest message accepted; small until a browser has paired. */
    void setMaxMessage(int bytes) {
        maxMessage = bytes;
    }

    /**
     * Reads end at this wall clock time (System.currentTimeMillis), whatever arrives meanwhile: the socket's timeout is
     * set to what is left before every frame. 0 removes it, and the caller sets its own socket timeout again.
     */
    void setDeadline(java.net.Socket s, long at) {
        deadlineSocket = s;
        deadline = at;
    }

    private void beforeFrame() throws IOException {
        long at = deadline;
        java.net.Socket s = deadlineSocket;
        if (at <= 0 || s == null) return;
        long left = at - System.currentTimeMillis();
        if (left <= 0) throw new java.net.SocketTimeoutException("deadline");
        s.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
    }

    /** The next text or binary message; answers pings on the way. Throws at close or a protocol error. */
    Message read() throws IOException {
        ByteArrayOutputStream whole = null;
        int type = 0;
        while (true) {
            beforeFrame();
            int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0f;
            if ((b1 & 0x80) == 0) throw new IOException("unmasked client frame");
            long len = b1 & 0x7f;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            if (len < 0 || len > maxMessage) throw new IOException("frame of " + len + " bytes");
            byte[] mask = new byte[4];
            in.readFully(mask);
            byte[] data = new byte[(int) len];
            in.readFully(data);
            for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            if (op == CLOSE) {
                closed = true;
                try {
                    send(CLOSE, data, 0, Math.min(data.length, 2));
                } catch (IOException ignored) { }
                throw new IOException("closed by the browser");
            }
            if (op == PING) {
                send(PONG, data, 0, data.length);
                continue;
            }
            if (op == PONG) continue;
            if (op == TEXT || op == BINARY) {
                if (whole != null) throw new IOException("new message inside a fragmented one");
                type = op;
                if (fin) return new Message(op, data);
                whole = new ByteArrayOutputStream();
                whole.write(data);
            } else if (op == 0) {
                if (whole == null) throw new IOException("continuation without a message");
                whole.write(data);
                if (whole.size() > maxMessage) throw new IOException("message too long");
                if (fin) return new Message(type, whole.toByteArray());
            } else {
                throw new IOException("opcode " + op);
            }
        }
    }

    void sendText(String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        send(TEXT, b, 0, b.length);
    }

    void send(int op, byte[] data, int off, int len) throws IOException {
        synchronized (sendLock) {
            byte[] head;
            if (len < 126) head = new byte[]{(byte) (0x80 | op), (byte) len};
            else if (len < 65536) head = new byte[]{(byte) (0x80 | op), 126, (byte) (len >> 8), (byte) len};
            else {
                head = new byte[10];
                head[0] = (byte) (0x80 | op);
                head[1] = 127;
                for (int i = 0; i < 8; i++) head[9 - i] = (byte) ((long) len >> (8 * i));
            }
            out.write(head);
            out.write(data, off, len);
            out.flush();
        }
    }

    /** Close politely, then the caller closes the socket. */
    void close() {
        if (closed) return;
        closed = true;
        try {
            send(CLOSE, new byte[]{0x03, (byte) 0xe8}, 0, 2);
        } catch (IOException ignored) { }
    }

    /** Bytes written up to flush() leave as one binary message. */
    OutputStream output() {
        return new OutputStream() {
            private final ByteArrayOutputStream pending = new ByteArrayOutputStream(1 << 16);
            @Override public void write(int b) {
                pending.write(b);
            }
            @Override public void write(byte[] b, int off, int len) {
                pending.write(b, off, len);
            }
            @Override public void flush() throws IOException {
                if (pending.size() == 0) return;
                byte[] b = pending.toByteArray();
                pending.reset();
                send(BINARY, b, 0, b.length);
            }
        };
    }

    /** The payloads of binary messages as one stream; text messages are skipped. */
    InputStream input() {
        return new InputStream() {
            private byte[] current = new byte[0];
            private int pos;
            private boolean fill() throws IOException {
                while (pos >= current.length) {
                    Message m;
                    try {
                        m = WebSocket.this.read();
                    } catch (java.io.EOFException e) {
                        return false;
                    }
                    if (m.type != BINARY) continue;
                    current = m.data;
                    pos = 0;
                }
                return true;
            }
            @Override public int read() throws IOException {
                if (!fill()) return -1;
                return current[pos++] & 0xff;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (!fill()) return -1;
                int n = Math.min(len, current.length - pos);
                System.arraycopy(current, pos, b, off, n);
                pos += n;
                return n;
            }
        };
    }
}
