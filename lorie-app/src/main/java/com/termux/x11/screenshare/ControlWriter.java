package com.termux.x11.screenshare;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Record format from MIT-licensed Rootshell AppleComCryptionChannel. */
final class ControlWriter {
    private final Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding");
    private final MessageDigest checksum = MessageDigest.getInstance("SHA-1");
    private final DataOutputStream sink;
    private int serial;

    ControlWriter(OutputStream sink, byte[] material) throws GeneralSecurityException {
        this.sink = new DataOutputStream(new BufferedOutputStream(sink));
        cbc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(material, 0, 16, "AES"),
                 new IvParameterSpec(material, 16, 16));
    }

    synchronized void send(byte[] payload) throws IOException {
        writeRecord(payload);
        sink.flush();
    }

    // Each transition retains its record serial and CBC state. Flush the wheel report as a batch.
    synchronized void sendPairs(byte[] down, byte[] up, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            writeRecord(down);
            writeRecord(up);
        }
        sink.flush();
    }

    private void writeRecord(byte[] payload) throws IOException {
        int blocks = (2 + payload.length + 20 + 15) / 16;
        if (blocks > 4095) throw new IOException("control message exceeds record limit");
        ByteBuffer clear = ByteBuffer.allocate(blocks * 16);
        clear.putShort((short)payload.length).put(payload);
        int prefix = clear.capacity() - 20;
        checksum.update(ByteBuffer.allocate(4).putInt(serial++).array());
        checksum.update(clear.array(), 0, prefix);
        clear.position(prefix);
        clear.put(checksum.digest());
        byte[] wire = cbc.update(clear.array());
        if (wire == null || wire.length != clear.capacity()) throw new IOException("incomplete CBC record");
        sink.writeShort(wire.length);
        sink.write(wire);
    }
}
