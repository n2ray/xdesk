package com.termux.x11.screenshare;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Receive direction of ControlWriter's record format, with independent CBC state and serial. */
final class ControlReader {
    private final Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
    private final MessageDigest digest = MessageDigest.getInstance("SHA-1");
    private final DataInputStream source;
    private int serial;

    ControlReader(DataInputStream source, byte[] material) throws GeneralSecurityException {
        this.source = source;
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(material, 0, 16, "AES"),
                new IvParameterSpec(material, 16, 16));
    }

    ByteBuffer next() throws IOException {
        int size = source.readUnsignedShort();
        if (size < 32 || size % 16 != 0) throw new IOException("Invalid control record size");
        byte[] wire = new byte[size];
        source.readFully(wire);
        byte[] clear = cipher.update(wire);
        int end = size - 20;
        digest.update(ByteBuffer.allocate(4).putInt(serial++).array());
        digest.update(clear, 0, end);
        if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(clear, end, size)))
            throw new IOException("Control record checksum mismatch");
        ByteBuffer record = ByteBuffer.wrap(clear);
        int length = record.getShort() & 65535;
        if (length > end - 2) throw new IOException("Invalid control payload length");
        record.limit(2 + length);
        return record.slice();
    }
}
