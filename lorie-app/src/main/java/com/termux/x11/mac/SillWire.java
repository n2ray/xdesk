/*
 * Derived from Sill (https://github.com/Saffsanity/sill, Sources/StreamProtocol/StreamMessage.swift) and its
 * Android client Sall (https://github.com/n2ray/sall, StreamWire.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Sill's message header: kind(1) timestamp(8, double) keyframe(1) length(4), big endian. Reading keeps one
 * payload buffer and grows it, so a stream of video frames allocates nothing per frame.
 */
final class SillWire {
    // Host → device
    static final int PARAMETER_SETS = 0, FRAME = 1, WINDOW_LIST = 2, THUMBNAIL = 3, APP_ICON = 4, APP_LIST = 5;
    // Device → host
    static final int SELECT_SOURCE = 6, LAUNCH_APP = 7, INPUT = 8, VIEWPORT = 9, PING = 10;
    static final int PONG = 11, TICK = 13, CURSOR_SHAPE = 14, WINDOW_COMMAND = 15, HOST_SETTINGS = 16;
    static final int MAC_INFO = 18, PAIR_REQUEST = 19, PAIR_RESULT = 20, GOODBYE = 22, HELLO = 23;
    static final int MAC_POINTER = 26, CLIPBOARD = 32, KEYFRAME_REQUEST = 44;

    static final int MAX_FRAME = 32 << 20, MAX_OTHER = 4 << 20, MAX_OUTGOING = 1 << 20;

    /** The message just read. payload is shared: valid until the next read. */
    static final class Message {
        int kind;
        double timestamp;
        boolean keyframe;
        int length;
        byte[] payload = new byte[64 << 10];

        byte[] copy() {
            byte[] out = new byte[length];
            System.arraycopy(payload, 0, out, 0, length);
            return out;
        }

        String text() {
            return new String(payload, 0, length, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private SillWire() { }

    static void read(DataInputStream in, Message m, int ceiling) throws IOException {
        int kind = in.read();
        if (kind < 0) throw new EOFException();
        m.kind = kind;
        m.timestamp = in.readDouble();
        m.keyframe = in.readUnsignedByte() == 1;
        long length = Integer.toUnsignedLong(in.readInt());
        if (length > Math.min(ceiling, kind == FRAME ? MAX_FRAME : MAX_OTHER)) throw new IOException("Sill payload too large");
        int n = (int) length;
        if (m.payload.length < n) m.payload = new byte[Math.max(n, m.payload.length * 2)];
        in.readFully(m.payload, 0, n);
        m.length = n;
    }

    static DataInputStream input(InputStream raw) {
        return new DataInputStream(new java.io.BufferedInputStream(raw, 64 << 10));
    }

    /** One message in one write, so TLS sends it in one record. */
    static void write(OutputStream out, int kind, byte[] payload) throws IOException {
        if (kind < 0 || kind > 255 || payload.length > MAX_OUTGOING) throw new IOException("Invalid Sill message");
        byte[] b = new byte[14 + payload.length];
        b[0] = (byte) kind;
        long t = Double.doubleToLongBits(System.currentTimeMillis() / 1000.0);
        for (int i = 0; i < 8; i++) b[1 + i] = (byte) (t >>> (56 - 8 * i));
        b[9] = 0;
        int n = payload.length;
        b[10] = (byte) (n >>> 24);
        b[11] = (byte) (n >>> 16);
        b[12] = (byte) (n >>> 8);
        b[13] = (byte) n;
        System.arraycopy(payload, 0, b, 14, n);
        out.write(b);
        out.flush();
    }
}
